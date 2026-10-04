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

    /** 녹음기가 설정. 채널 메시지(노트·페달 등)만 전달하고 timestamp는 System.nanoTime 기준. */
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
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) =
            parser.feed(msg, offset, count, timestamp)
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (device == null && info.type == MidiDeviceInfo.TYPE_USB) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) close()
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
        handler.post { devices().firstOrNull { it.type == MidiDeviceInfo.TYPE_USB }?.let(::open) }
    }

    private fun devices(): List<MidiDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            midiManager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            @Suppress("DEPRECATION")
            midiManager.devices.toList()
        }

    /** handler 스레드에서만 호출 */
    private fun open(info: MidiDeviceInfo) {
        if (device != null) return
        midiManager.openDevice(info, { dev ->
            if (dev == null) { Log.w(TAG, "openDevice 실패"); return@openDevice }
            device = dev
            if (info.outputPortCount > 0) outputPort = dev.openOutputPort(0)?.also { it.connect(receiver) }
            if (info.inputPortCount > 0) inputPort = dev.openInputPort(0)
            Log.i(TAG, "피아노 연결: out=${outputPort != null} in=${inputPort != null}")
            _connected.value = outputPort != null
        }, handler)
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
    }
}
