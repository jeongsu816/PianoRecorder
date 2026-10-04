package app.pianorecorder.probe

/**
 * MIDI 1.0 바이트 스트림 → 완결된 메시지 단위로 자르는 파서.
 *
 * android.media.midi의 onSend는 "메시지 경계"를 보장하지 않는다(여러 메시지가 한 번에 오거나,
 * running status로 상태 바이트가 생략될 수 있음). 그래서 상태를 유지하며 바이트 단위로 조립한다.
 * 본 앱의 녹음(SMF 기록)에서도 그대로 재사용할 수 있게 Probe 전용 코드와 분리했다.
 */
class MidiParser(private val onMessage: (msg: ByteArray, timestampNanos: Long) -> Unit) {
    private var runningStatus = 0
    private val buf = ByteArray(3)
    private var count = 0
    private var needed = 0
    private var inSysEx = false

    fun feed(data: ByteArray, offset: Int, length: Int, timestampNanos: Long) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> onMessage(byteArrayOf(b.toByte()), timestampNanos) // 실시간 메시지는 어디든 끼어들 수 있음
                b == 0xF0 -> { inSysEx = true; runningStatus = 0; count = 0 }
                b == 0xF7 -> inSysEx = false // SysEx 본문은 Probe/녹음 모두 필요 없어 버림
                b >= 0x80 -> {
                    inSysEx = false
                    runningStatus = if (b < 0xF0) b else 0 // 시스템 공통 메시지는 running status를 해제
                    buf[0] = b.toByte()
                    count = 1
                    needed = dataLength(b) + 1
                    if (count == needed) emit(timestampNanos)
                }
                inSysEx -> Unit
                else -> {
                    if (count == 0) {
                        if (runningStatus == 0) continue // 상태 바이트 없이 온 데이터 → 버림
                        buf[0] = runningStatus.toByte()
                        count = 1
                        needed = dataLength(runningStatus) + 1
                    }
                    buf[count++] = b.toByte()
                    if (count == needed) emit(timestampNanos)
                }
            }
        }
    }

    private fun emit(ts: Long) {
        onMessage(buf.copyOf(count), ts)
        count = 0
    }

    private fun dataLength(status: Int): Int = when (status and 0xF0) {
        0xC0, 0xD0 -> 1
        0xF0 -> when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            else -> 0
        }
        else -> 2
    }

    companion object {
        private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

        fun describe(m: ByteArray): String {
            val s = m[0].toInt() and 0xFF
            val d1 = if (m.size > 1) m[1].toInt() and 0xFF else 0
            val d2 = if (m.size > 2) m[2].toInt() and 0xFF else 0
            val ch = (s and 0x0F) + 1
            return when (s and 0xF0) {
                0x90 -> if (d2 > 0) "NoteOn  ch$ch ${noteName(d1)}($d1) vel=$d2"
                        else "NoteOff ch$ch ${noteName(d1)}($d1) (vel0)"
                0x80 -> "NoteOff ch$ch ${noteName(d1)}($d1) vel=$d2"
                0xB0 -> when (d1) {
                    64 -> "CC64 서스테인 ch$ch = $d2"
                    66 -> "CC66 소스테누토 ch$ch = $d2"
                    67 -> "CC67 소프트 ch$ch = $d2"
                    else -> "CC$d1 ch$ch = $d2"
                }
                0xC0 -> "ProgramChange ch$ch = $d1"
                0xE0 -> "PitchBend ch$ch = ${(d2 shl 7 or d1) - 8192}"
                else -> m.joinToString(" ") { "%02X".format(it) }
            }
        }
    }
}
