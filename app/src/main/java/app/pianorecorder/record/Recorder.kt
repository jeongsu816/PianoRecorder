package app.pianorecorder.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import app.pianorecorder.midi.MidiEvent
import app.pianorecorder.midi.PianoConnection
import app.pianorecorder.midi.Smf
import app.pianorecorder.storage.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 오디오(USB 입력 → AAC → m4a)와 MIDI(피아노 출력 포트 → SMF)를 동시에 녹음한다.
 *
 * 시간 맞춤: Probe에서 startRecording() 호출 시각을 기준으로 잡으면 오디오가 MIDI보다 약 0.1초
 * 앞서는 것을 확인했다. 그래서 AudioRecord.getTimestamp()가 알려 주는 "프레임 n이 녹음된 nanoTime"으로
 * 오디오 첫 프레임의 실제 시각을 역산하고, MIDI 이벤트 시각(onSend timestamp, 같은 nanoTime 기준)을
 * 그 시각 기준으로 바꿔 SMF에 쓴다. → 두 파일의 0초가 같은 순간을 가리킨다.
 */
class Recorder(
    private val context: Context,
    private val piano: PianoConnection,
    private val store: RecordingStore,
) {
    sealed interface State {
        data object Idle : State
        /** [startedAtElapsed]: 화면의 경과 시간 표시용 (SystemClock.elapsedRealtime) */
        data class Recording(
            val name: String,
            val volume: String,
            val startedAtElapsed: Long,
            val withAudio: Boolean,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private class Session(
        val name: String,
        val midiUri: Uri,
        val audioUri: Uri?,
        val startNanos: Long,
        val initialSetup: List<ByteArray>,
    ) {
        val midiEvents = ArrayList<Pair<Long, ByteArray>>() // (nanoTime, 메시지), 동기화 필요
        @Volatile var running = true
        @Volatile var audioFrame0Nanos: Long? = null
        @Volatile var audioError: Throwable? = null
        var audioThread: Thread? = null
    }

    private var session: Session? = null

    /** USB 오디오 입력 장치. 없으면 MIDI만 녹음한다. */
    private fun usbInput(): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** [volume]: 저장할 MediaStore 볼륨. 호출 전 RECORD_AUDIO 권한 확인 필요. 실패 시 예외. */
    fun start(volume: String): String {
        check(session == null) { "이미 녹음 중" }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val input = usbInput()
        val midiUri = store.createPending(name, RecordingStore.MIDI_EXT, volume)
        val audioUri = input?.let {
            try { store.createPending(name, RecordingStore.AUDIO_EXT, volume) }
            catch (e: Exception) { store.discard(midiUri); throw e }
        }

        val s = Session(name, midiUri, audioUri, System.nanoTime(), piano.currentSetup())
        piano.listener = { msg, ts -> synchronized(s.midiEvents) { s.midiEvents += ts to msg } }
        if (input != null && audioUri != null) {
            s.audioThread = Thread({ audioLoop(s, input, audioUri) }, "record-audio").apply { start() }
        } else {
            Log.w(TAG, "USB 오디오 입력 없음 → MIDI만 녹음")
        }
        session = s
        _state.value = State.Recording(name, volume, SystemClock.elapsedRealtime(), withAudio = input != null)
        return name
    }

    /** 녹음을 끝내고 두 파일을 공개(IS_PENDING=0)한다. 블로킹 — IO 스레드에서 호출. */
    fun stop() {
        val s = session ?: return
        piano.listener = null
        s.running = false
        s.audioThread?.join(5000)
        session = null
        try {
            // 오디오 시각 정보를 못 얻었으면(오디오 없음/실패) 녹음 시작 호출 시각으로 대체
            val zero = s.audioFrame0Nanos ?: s.startNanos
            val events = s.initialSetup.map { MidiEvent(0, it) } + synchronized(s.midiEvents) {
                s.midiEvents.map { (ts, msg) -> MidiEvent((ts - zero) / 1_000_000, msg) }
            }
            context.contentResolver.openOutputStream(s.midiUri, "w")!!.use { Smf.write(events, it) }
            store.publish(s.midiUri)

            if (s.audioUri != null) {
                if (s.audioError == null) store.publish(s.audioUri)
                else { Log.e(TAG, "오디오 녹음 실패 → m4a 버림", s.audioError); store.discard(s.audioUri) }
            }
            Log.i(TAG, "저장 완료 ${s.name}: MIDI ${events.size}개, 오디오 기준점 보정 " +
                "${s.audioFrame0Nanos?.let { "%.1fms".format((it - s.startNanos) / 1e6) } ?: "없음"}")
        } catch (e: Exception) {
            store.discard(s.midiUri)
            s.audioUri?.let(store::discard)
            throw e
        } finally {
            _state.value = State.Idle
        }
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop(s: Session, input: AudioDeviceInfo, uri: Uri) {
        var rec: AudioRecord? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            // FP-30X는 44100Hz 스테레오 고정([확인]). 다른 장치를 대비해 장치가 알려 주는 값을 따른다.
            val rate = input.sampleRates.let { if (it.isEmpty() || 44100 in it) 44100 else it.max() }
            val channels = if (input.channelCounts.isEmpty() || 2 in input.channelCounts) 2 else 1
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            // UNPROCESSED는 미지원([확인]) → VOICE_RECOGNITION(자동 음량 조절 없이 받는 소스)
            val source = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf * 4, rate * channels * 2 / 2)) // 0.5초 이상 여유
                .build()
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
            rec.preferredDevice = input

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            pfd = context.contentResolver.openFileDescriptor(uri, "rw")!!
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bytesPerFrame = 2 * channels
            val info = MediaCodec.BufferInfo()
            val ts = AudioTimestamp()
            var track = -1
            var framesQueued = 0L
            var inputDone = false

            rec.startRecording()
            while (true) {
                // 1) 입력: AudioRecord → 인코더 입력 버퍼
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val pts = framesQueued * 1_000_000 / rate
                        if (s.running) {
                            val want = buf.capacity() - buf.capacity() % bytesPerFrame
                            val n = rec.read(buf, want)
                            check(n >= 0) { "AudioRecord.read 오류 $n" }
                            framesQueued += n / bytesPerFrame
                            codec.queueInputBuffer(idx, 0, n, pts, 0)
                        } else {
                            rec.stop()
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                // 오디오 첫 프레임의 실제 시각 (한 번만, 위치가 0보다 커진 뒤)
                if (s.audioFrame0Nanos == null &&
                    rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                    ts.framePosition > 0
                ) {
                    s.audioFrame0Nanos = ts.nanoTime - ts.framePosition * 1_000_000_000 / rate
                }
                // 2) 출력: 인코더 → MP4 컨테이너
                var outputDone = false
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        continue
                    }
                    if (idx < 0) continue
                    val out = codec.getOutputBuffer(idx)!!
                    // 코덱 설정 데이터(CSD)는 addTrack의 format에 이미 들어 있음
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                }
                if (outputDone) break
            }
            muxer.stop()
        } catch (e: Throwable) {
            s.audioError = e
            Log.e(TAG, "오디오 녹음 오류", e)
        } finally {
            runCatching { rec?.release() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            runCatching { pfd?.close() }
        }
    }

    companion object {
        private const val TAG = "Recorder"
        /** AAC-LC 스테레오 192kbps: 1분 ≈ 1.4MB. 피아노 음색에 충분하고 메신저 첨부에도 부담 없음 */
        const val BIT_RATE = 192_000
    }
}
