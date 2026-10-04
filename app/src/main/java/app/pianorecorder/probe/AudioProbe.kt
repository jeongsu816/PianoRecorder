package app.pianorecorder.probe

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * 확인 항목
 *  - USB 오디오 입·출력 장치의 샘플레이트/채널 수
 *  - AudioRecord를 USB 입력으로 지정해 녹음 (MIDI 수신과 동시에)
 *  - 루프 여부: USB 출력으로 보낸 소리가 USB 입력으로 되돌아오는지
 */
class AudioProbe(
    private val context: Context,
    private val log: ProbeLog,
    private val midi: MidiProbe,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var recording = false
    private var recordThread: Thread? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    fun scan() {
        log.i("── USB 장치 (UsbManager) ──")
        val usb = context.getSystemService(UsbManager::class.java).deviceList.values
        if (usb.isEmpty()) log.i("  없음")
        for (d in usb) {
            // VID/PID/이름은 권한 없이 읽힘 (시리얼 번호만 권한 필요)
            log.i("  VID=0x%04X PID=0x%04X '%s' / '%s' class=%d 인터페이스 %d개".format(
                d.vendorId, d.productId, d.manufacturerName, d.productName, d.deviceClass, d.interfaceCount))
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                // class 1 = Audio. subclass 1=Control, 2=Streaming, 3=MIDI Streaming
                log.i("    itf#${itf.id} alt=${itf.alternateSetting} class=${itf.interfaceClass} sub=${itf.interfaceSubclass} ep=${itf.endpointCount}")
            }
        }

        log.i("── 오디오 입력 장치 ──")
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { log.i("  ${describe(it)}") }
        log.i("── 오디오 출력 장치 ──")
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { log.i("  ${describe(it)}") }
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        log.i("UNPROCESSED 소스 지원: $unprocessed")
    }

    private fun describe(d: AudioDeviceInfo): String {
        // 빈 배열 = "임의 값 지원"(제한 없음)이라는 뜻
        fun IntArray.fmt() = if (isEmpty()) "any" else joinToString(",")
        return "${typeName(d.type)} '${d.productName}' id=${d.id}" +
            " rates=[${d.sampleRates.fmt()}] ch=[${d.channelCounts.fmt()}] enc=[${d.encodings.fmt()}]"
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        else -> "type$t"
    }

    private fun isUsb(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun usbInput() = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull(::isUsb)
    private fun usbOutput() = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isUsb)

    /**
     * USB 입력으로 녹음하면서 MIDI 수신 개수와 오디오 레벨을 1초마다 로그.
     * 둘 다 늘어나면 "동시 동작" 확인. WAV는 앱 전용 폴더에 저장(adb pull로 꺼내 들어볼 수 있음).
     */
    @SuppressLint("MissingPermission") // 호출 전에 Activity에서 RECORD_AUDIO 확인
    fun startRecording() {
        if (recording) return
        val dev = usbInput()
        if (dev == null) log.i("⚠ USB 오디오 입력이 없습니다. 기본 입력(내장 마이크일 수 있음)으로 진행")

        val rate = pickRate(dev)
        val stereo = dev == null || dev.channelCounts.isEmpty() || dev.channelCounts.contains(2)
        val channelMask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val channels = if (stereo) 2 else 1
        val source = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { log.i("getMinBufferSize 실패: $minBuf (rate=$rate ch=$channels)"); return }

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) { log.i("AudioRecord 생성 실패: $e"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { log.i("AudioRecord 초기화 실패"); rec.release(); return }
        if (dev != null) log.i("setPreferredDevice(${dev.productName}) = ${rec.setPreferredDevice(dev)}")

        val dir = context.getExternalFilesDir(null)!!
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "probe_$name.wav")

        midi.channelMessageCount.set(0)
        recording = true
        _isRecording.value = true
        recordThread = Thread({ recordLoop(rec, file, rate, channels, source) }, "record").apply { start() }
    }

    private fun pickRate(dev: AudioDeviceInfo?): Int {
        val rates = dev?.sampleRates ?: intArrayOf()
        return when {
            rates.isEmpty() || rates.contains(48000) -> 48000
            rates.contains(44100) -> 44100
            else -> rates.max()
        }
    }

    private fun recordLoop(rec: AudioRecord, file: File, rate: Int, channels: Int, source: Int) {
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        out.write(ByteArray(44)) // WAV 헤더 자리, 끝나고 채움
        val buf = ShortArray(rate * channels / 10) // 100ms
        val bytes = ByteArray(buf.size * 2)
        var totalFrames = 0L
        var peak = 0
        var lastReport = System.nanoTime()
        try {
            rec.startRecording()
            val startNs = System.nanoTime()
            val srcName = if (source == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "VOICE_RECOGNITION"
            log.i("● 녹음 시작 rate=$rate ch=$channels source=$srcName → ${file.name}")
            // 실제 라우팅은 시작 후에야 확정됨 → USB로 갔는지 여기서 확인
            log.i("  실제 입력 장치(routedDevice): ${rec.routedDevice?.let { describe(it) }}")
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { log.i("read 오류: $n"); break }
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    if (abs(s) > peak) peak = abs(s)
                    bytes[i * 2] = s.toByte()
                    bytes[i * 2 + 1] = (s shr 8).toByte()
                }
                out.write(bytes, 0, n * 2)
                totalFrames += n / channels
                val now = System.nanoTime()
                if (now - lastReport >= 1_000_000_000L) {
                    log.i("  ● %5.1fs 피크 %s, MIDI 이벤트 누계 %d".format(
                        (now - startNs) / 1e9, dbfs(peak), midi.channelMessageCount.get()))
                    peak = 0
                    lastReport = now
                }
            }
            rec.stop()
            log.i("■ 녹음 종료: ${"%.1f".format(totalFrames.toDouble() / rate)}초, MIDI 이벤트 ${midi.channelMessageCount.get()}개")
            log.i("  파일: ${file.absolutePath}")
        } catch (e: Exception) {
            log.i("녹음 오류: $e")
        } finally {
            rec.release()
            writeWavHeader(out, rate, channels, totalFrames * channels * 2)
            out.close()
            recording = false
            _isRecording.value = false
        }
    }

    private fun dbfs(peak: Int) =
        if (peak == 0) "-inf dBFS" else "%.1f dBFS".format(20 * log10(peak / 32768.0))

    fun stopRecording() {
        recording = false
        recordThread?.join(2000)
        recordThread = null
    }

    /**
     * 녹음 중에 USB 출력으로 1kHz 사인파(-20dBFS)를 2초 보낸다.
     * 피아노를 치지 않은 상태에서 이 2초 동안 녹음 피크가 올라가면 → 피아노가 USB 입력을 USB 출력으로 되돌려 보냄(루프).
     */
    fun loopTest() {
        if (!recording) { log.i("먼저 녹음을 시작하고 피아노를 치지 않은 상태에서 실행하세요"); return }
        val out = usbOutput()
        Thread({
            val rate = 48000
            val samples = ShortArray(rate * 2) { i -> (3277 * sin(2 * PI * 1000 * i / rate)).toInt().toShort() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                track.write(samples, 0, samples.size)
                out?.let { track.preferredDevice = it }
                log.i("▶ 루프 테스트: 1kHz 사인파 2초 → ${out?.productName ?: "기본 출력"}")
                track.play()
                log.i("  실제 출력 장치(routedDevice): ${track.routedDevice?.let { describe(it) }}")
                Thread.sleep(2200)
                log.i("▶ 루프 테스트 끝 — 위 2초 구간의 녹음 피크를 확인하세요")
            } catch (e: Exception) {
                log.i("루프 테스트 오류: $e")
            } finally {
                track.release()
            }
        }, "loop").start()
    }

    private fun writeWavHeader(f: RandomAccessFile, rate: Int, channels: Int, dataLen: Long) {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        b.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        f.seek(0)
        f.write(b.array())
    }
}
