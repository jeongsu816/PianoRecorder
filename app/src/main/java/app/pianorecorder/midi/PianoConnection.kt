package app.pianorecorder.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB로 연결된 피아노(MIDI)를 자동으로 열고 닫는다.
 *
 * - 케이블을 뺐다 꽂으면 장치 id가 바뀌므로([확인]) id를 기억하지 않고, 추가/제거 콜백마다
 *   "USB MIDI 장치 중 첫 번째"를 피아노로 간주한다. 집에 USB MIDI 장치는 피아노 하나뿐이라는 전제.
 * - 포트 방향은 장치 기준: OutputPort = 피아노가 보냄(앱이 받음), InputPort = 피아노가 받음(앱이 보냄).
 */
class PianoConnection(context: Context) {
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("midi").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    @Volatile private var inputPort: MidiInputPort? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 녹음기가 설정. 채널 메시지(노트·페달 등)만 전달하고 시각은 받은 순간의 System.nanoTime. */
    @Volatile var listener: ((msg: ByteArray, timestampNanos: Long) -> Unit)? = null

    /**
     * 채널별 마지막 음색/볼륨 설정. 피아노는 음색을 바꿀 때 CC0(Bank MSB) → CC32(Bank LSB) →
     * Program Change를 보낸다([확인]). 녹음 시작 전에 고른 음색은 녹음에 안 들어가므로,
     * 앱이 켜진 뒤 본 마지막 값을 기억해 두었다가 녹음 맨 앞에 넣는다(보완책 — 앱을 켜기 전 선택은 모름).
     */
    private val setup = sortedMapOf<Int, ByteArray>() // key = 채널*4 + 순서(0:CC0 1:CC32 2:PC 3:CC7)

    private val parser = MidiParser { msg, ts ->
        val status = msg[0].toInt() and 0xFF
        if (status !in 0x80..0xEF) return@MidiParser
        val ch = status and 0x0F
        val slot = when {
            status and 0xF0 == 0xC0 -> 2
            status and 0xF0 == 0xB0 -> when (msg[1].toInt()) { 0 -> 0; 32 -> 1; 7 -> 3; else -> -1 }
            else -> -1
        }
        if (slot >= 0) synchronized(setup) { setup[ch * 4 + slot] = msg }
        listener?.invoke(msg, ts)
    }

    /** 녹음 시작 시점의 음색 설정 (Bank MSB → LSB → Program → Volume 순, 채널 순) */
    fun currentSetup(): List<ByteArray> = synchronized(setup) { setup.values.toList() }

    private val receiver = object : MidiReceiver() {
        /**
         * OS가 넘겨주는 timestamp 대신 "받은 시각"을 쓴다.
         *
         * 갤럭시 S20(Android 13)에서는 timestamp가 이 메시지가 아니라 **직전 메시지를 받은 시각**으로 온다
         * (2026-10-05 로그 확인: lag = 직전 메시지와의 간격). 그래서 화음의 첫 음이 직전 뗌 시각으로 찍혀
         * 두 음이 ~100ms 어긋나 기록됐다. 전달 자체는 늦지 않아서 받은 시각이 실제 시각에 가깝다.
         * 폴드8(Android 17)은 timestamp가 정상이고 받은 시각과의 차이도 1~4ms라 이쪽으로 통일해도 손해가 없다.
         */
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val now = System.nanoTime()
            if (Log.isLoggable(DIAG_TAG, Log.DEBUG)) {
                val hex = (offset until offset + count).joinToString(" ") { "%02X".format(msg[it]) }
                Log.d(DIAG_TAG, "onSend n=$count lag=${(now - timestamp) / 1000}µs [$hex]")
            }
            parser.feed(msg, offset, count, now)
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            Log.i(TAG, "장치 추가 id=${info.id} type=${info.type}")
            if (info.type == MidiDeviceInfo.TYPE_USB) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            Log.i(TAG, "장치 제거 id=${info.id}")
            if (device?.info?.id == info.id) {
                close()
                // 다시 꽂을 때 "추가"가 "제거"보다 먼저 오면 새 장치를 못 연 채로 남으므로 한 번 더 찾는다
                openFirstUsb()
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            midiManager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { handler.post(it) }, callback)
        } else {
            @Suppress("DEPRECATION")
            midiManager.registerDeviceCallback(callback, handler)
        }
        // 콜백 등록 전부터 꽂혀 있던 장치
        handler.post(::openFirstUsb)
    }

    private fun devices(): List<MidiDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            midiManager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            @Suppress("DEPRECATION")
            midiManager.devices.toList()
        }

    private fun openFirstUsb() {
        devices().firstOrNull { it.type == MidiDeviceInfo.TYPE_USB }?.let(::open)
    }

    /** openDevice가 비동기라서, 결과가 오기 전 두 번째 open을 막는 표시 */
    private var opening = false

    /**
     * handler 스레드에서만 호출.
     *
     * 주의: 같은 장치를 두 번 열면 두 번째의 openInputPort가 null이다(입력 포트는 한 곳만 열 수 있음).
     * 예전 코드는 그 null로 정상 포트를 덮어써서 "녹음(출력 포트)은 되는데 피아노 재생(입력 포트)만
     * 소리 없이 실패"했다. → [opening]으로 중복 open을 막고, 두 포트가 모두 열려야 연결로 본다.
     */
    private fun open(info: MidiDeviceInfo, attempt: Int = 1) {
        if (device != null || opening) return
        opening = true
        midiManager.openDevice(info, { dev ->
            opening = false
            if (dev == null) { Log.w(TAG, "openDevice 실패 (시도 $attempt)"); retry(info, attempt); return@openDevice }
            device = dev
            outputPort = if (info.outputPortCount > 0) dev.openOutputPort(0)?.also { it.connect(receiver) } else null
            inputPort = if (info.inputPortCount > 0) dev.openInputPort(0) else null
            Log.i(TAG, "피아노 연결 id=${info.id}: out=${outputPort != null} in=${inputPort != null} (시도 $attempt)")
            if (outputPort != null && inputPort != null) _connected.value = true
            else { close(); retry(info, attempt) }
        }, handler)
    }

    /** 꽂은 직후에는 장치가 덜 준비됐을 수 있어 잠시 뒤 다시 시도 [추측] */
    private fun retry(info: MidiDeviceInfo, attempt: Int) {
        if (attempt >= 5) { Log.e(TAG, "피아노 연결 포기"); return }
        handler.postDelayed({
            if (devices().any { it.id == info.id }) open(info, attempt + 1)
        }, 500L * attempt)
    }

    private fun close() {
        _connected.value = false
        outputPort?.run { disconnect(receiver); close() }
        outputPort = null
        inputPort?.close()
        inputPort = null
        device?.close()
        device = null
        Log.i(TAG, "피아노 연결 해제")
    }

    /** 피아노로 송신. 연결이 없으면 false. 어느 스레드에서 불러도 됨(MidiInputPort.send는 스레드 안전). */
    fun send(msg: ByteArray): Boolean {
        val port = inputPort ?: return false
        return try {
            port.send(msg, 0, msg.size)
            true
        } catch (e: Exception) {
            Log.w(TAG, "송신 실패", e)
            false
        }
    }

    fun release() {
        midiManager.unregisterDeviceCallback(callback)
        handler.post { close(); thread.quitSafely() }
    }

    companion object {
        private const val TAG = "PianoConnection"
        /** 진단 로그. `adb shell setprop log.tag.MidiIn DEBUG`로 켠다 (기본은 꺼짐) */
        private const val DIAG_TAG = "MidiIn"
    }
}
