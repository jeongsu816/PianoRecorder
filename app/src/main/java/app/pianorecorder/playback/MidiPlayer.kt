package app.pianorecorder.playback

import app.pianorecorder.midi.MidiEvent
import app.pianorecorder.midi.PianoConnection
import java.util.concurrent.locks.LockSupport

/**
 * 저장된 MIDI 이벤트를 원래 간격대로 피아노에 보낸다 ("피아노로 재생").
 *
 * Android MIDI API에는 공개 스케줄러가 없어서[추측] 전용 스레드가 System.nanoTime 기준 목표 시각까지
 * parkNanos로 기다렸다가 보낸다. 매번 "시작 시각 + 이벤트 시각"으로 목표를 잡으므로 대기 오차가
 * 누적되지 않는다(이전 이벤트 기준 상대 대기는 오차가 쌓임).
 */
class MidiPlayer(private val piano: PianoConnection) {
    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false

    /** [onEnd]는 재생 스레드에서 호출된다. sendFailed = 피아노로 보내지 못해 중단됨 */
    fun play(events: List<MidiEvent>, onEnd: (sendFailed: Boolean) -> Unit) {
        stop()
        stopRequested = false
        thread = Thread({
            val start = System.nanoTime()
            var failed = false
            try {
                for (e in events) {
                    val target = start + e.timeMs * 1_000_000
                    while (!stopRequested) {
                        val wait = target - System.nanoTime()
                        if (wait <= 0) break
                        LockSupport.parkNanos(wait)
                    }
                    if (stopRequested) break
                    if (!piano.send(e.data)) { failed = true; break } // 케이블이 빠졌거나 송신 포트 없음
                }
            } finally {
                allNotesOff()
                onEnd(failed)
            }
        }, "midi-play").apply { start() }
    }

    fun stop() {
        stopRequested = true
        thread?.takeIf { it !== Thread.currentThread() }?.let { LockSupport.unpark(it); it.join(1000) }
        thread = null
    }

    /** 중지 시 음이 계속 울리지 않도록 16채널 모두 서스테인 해제 + All Notes Off */
    private fun allNotesOff() {
        for (ch in 0 until 16) {
            piano.send(byteArrayOf((0xB0 or ch).toByte(), 64, 0))
            piano.send(byteArrayOf((0xB0 or ch).toByte(), 123, 0))
        }
    }
}
