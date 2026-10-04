package app.pianorecorder.probe

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicInteger

/**
 * 확인 항목
 *  - FP-30X가 MidiManager에 보이는지, 포트 구성
 *  - 피아노 출력 포트 → 앱 수신 (건반/페달 이벤트)
 *  - 앱 → 피아노 입력 포트 송신 시 소리가 나는지
 *
 * 용어 주의: Android MIDI API의 포트 방향은 "장치 기준"이다.
 *  - MidiOutputPort = 장치가 내보내는 포트 → 앱이 receiver를 연결해 "받는" 쪽
 *  - MidiInputPort  = 장치가 받는 포트   → 앱이 send()로 "보내는" 쪽
 */
class MidiProbe(context: Context, private val log: ProbeLog) {
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("midi").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    private var inputPort: MidiInputPort? = null

    /** 녹음과의 동시 동작 확인용: 오디오 녹음 중 수신한 채널 메시지 개수 */
    val channelMessageCount = AtomicInteger()
    private val activeSensingCount = AtomicInteger()
    private val clockCount = AtomicInteger()

    @Volatile var logEvents = true

    private val parser = MidiParser { msg, ts ->
        when (msg[0].toInt() and 0xFF) {
            0xFE -> activeSensingCount.incrementAndGet() // Roland는 ~300ms마다 보냄 → 로그에서 제외
            0xF8 -> clockCount.incrementAndGet()
            else -> {
                channelMessageCount.incrementAndGet()
                if (logEvents) {
                    val lagUs = (System.nanoTime() - ts) / 1000
                    log.i("MIDI ← ${MidiParser.describe(msg)}  (ts→수신 ${lagUs}µs)")
                }
            }
        }
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.feed(msg, offset, count, timestamp)
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = log.i("MIDI 장치 추가: ${summary(info)}")
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            log.i("MIDI 장치 제거: ${summary(info)}")
            if (device?.info?.id == info.id) close()
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            midiManager.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { handler.post(it) }, deviceCallback
            )
        } else {
            @Suppress("DEPRECATION")
            midiManager.registerDeviceCallback(deviceCallback, handler)
        }
    }

    private fun devices(): List<MidiDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            midiManager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            @Suppress("DEPRECATION")
            midiManager.devices.toList()
        }

    fun scan() {
        val list = devices()
        log.i("── MIDI 장치 ${list.size}개 ──")
        for (info in list) {
            log.i("  ${summary(info)}")
            val p = info.properties
            log.i("    manufacturer=${p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)}" +
                " product=${p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)}" +
                " version=${p.getString(MidiDeviceInfo.PROPERTY_VERSION)}")
            usbDeviceOf(info)?.let {
                log.i("    USB VID=0x%04X PID=0x%04X".format(it.vendorId, it.productId))
            }
            for (port in info.ports) {
                val dir = if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) "출력(→앱)" else "입력(←앱)"
                log.i("    port#${port.portNumber} $dir name='${port.name}'")
            }
        }
    }

    private fun summary(info: MidiDeviceInfo): String {
        val type = when (info.type) {
            MidiDeviceInfo.TYPE_USB -> "USB"
            MidiDeviceInfo.TYPE_BLUETOOTH -> "BT"
            MidiDeviceInfo.TYPE_VIRTUAL -> "VIRTUAL"
            else -> "type${info.type}"
        }
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        return "id=${info.id} [$type] '$name' out=${info.outputPortCount} in=${info.inputPortCount}"
    }

    private fun usbDeviceOf(info: MidiDeviceInfo): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            info.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            info.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE)
        }

    /** USB MIDI 장치 중 첫 번째(=피아노로 가정)를 열고 출력 포트 0에 receiver 연결, 입력 포트 0을 연다. */
    fun open() {
        if (device != null) { log.i("이미 열려 있음: ${summary(device!!.info)}"); return }
        val info = devices().firstOrNull { it.type == MidiDeviceInfo.TYPE_USB }
        if (info == null) { log.i("USB MIDI 장치가 없습니다. 케이블/피아노 전원 확인"); return }
        log.i("열기 시도: ${summary(info)}")
        midiManager.openDevice(info, { dev ->
            if (dev == null) { log.i("openDevice 실패 (null)"); return@openDevice }
            device = dev
            if (info.outputPortCount > 0) {
                outputPort = dev.openOutputPort(0)?.also { it.connect(receiver) }
                log.i(if (outputPort != null) "출력 포트 0 연결됨 → 건반을 눌러 보세요" else "출력 포트 0 열기 실패")
            }
            if (info.inputPortCount > 0) {
                inputPort = dev.openInputPort(0)
                log.i(if (inputPort != null) "입력 포트 0 열림 (송신 가능)" else "입력 포트 0 열기 실패(다른 앱이 사용 중?)")
            }
        }, handler)
    }

    fun close() {
        outputPort?.disconnect(receiver)
        outputPort?.close(); outputPort = null
        inputPort?.close(); inputPort = null
        device?.close(); device = null
        log.i("MIDI 장치 닫음 (ActiveSensing ${activeSensingCount.get()}회, Clock ${clockCount.get()}회 수신)")
    }

    /**
     * C4-E4-G4-C5를 차례로 보내고 화음으로 마무리. 피아노에서 소리가 나면 "MIDI로 재생" 경로 확인.
     * 타이밍은 본 앱 재생과 같은 방식(nanoTime 기준으로 직접 대기)으로 맞춘다.
     */
    fun sendTestNotes() {
        val port = inputPort ?: run { log.i("입력 포트가 열려 있지 않습니다. 먼저 'MIDI 열기'"); return }
        handler.post {
            try {
                log.i("MIDI → 테스트음 송신 시작")
                val notes = intArrayOf(60, 64, 67, 72)
                for (n in notes) {
                    send(port, 0x90, n, 80); Thread.sleep(300)
                    send(port, 0x80, n, 0); Thread.sleep(50)
                }
                for (n in notes) send(port, 0x90, n, 70)
                Thread.sleep(1000)
                allNotesOff(port)
                log.i("MIDI → 테스트음 송신 완료")
            } catch (e: Exception) {
                log.i("송신 오류: $e")
            }
        }
    }

    private fun send(port: MidiInputPort, status: Int, d1: Int, d2: Int) {
        port.send(byteArrayOf(status.toByte(), d1.toByte(), d2.toByte()), 0, 3)
    }

    /** 재생 중지 시 음이 남지 않게: 16채널 모두 서스테인 해제 + All Notes Off */
    private fun allNotesOff(port: MidiInputPort) {
        for (ch in 0 until 16) {
            send(port, 0xB0 or ch, 64, 0)
            send(port, 0xB0 or ch, 123, 0)
        }
    }

    fun release() {
        close()
        midiManager.unregisterDeviceCallback(deviceCallback)
        thread.quitSafely()
    }
}
