package app.pianorecorder.midi

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** 녹음 시작(오디오 첫 프레임) 기준 시각 [timeMs]에 발생한 MIDI 채널 메시지 1개 */
class MidiEvent(val timeMs: Long, val data: ByteArray)

/**
 * Standard MIDI File 읽기/쓰기.
 *
 * 쓰기: Format 0(트랙 1개). 템포 120(4분음표 = 500ms)에 분해능 500 tick/4분음표로 두어
 * 1 tick = 정확히 1ms가 되게 했다. 녹음은 박자와 무관한 실시간 연주라서 "마디"보다
 * "밀리초"가 자연스럽고, DAW로 열면 템포 120의 악보로 보인다.
 *
 * 읽기: 이 앱이 쓴 파일이 주 대상이지만 Format 1, 템포 변경, running status도 처리한다
 * (USB 메모리에서 꺼낸 피아노 녹음 SMF 등을 넣어도 재생되도록).
 */
object Smf {
    const val PPQ = 500
    const val TEMPO_US = 500_000

    fun write(events: List<MidiEvent>, out: OutputStream) {
        val track = ByteArrayOutputStream()
        // 템포 메타 이벤트: FF 51 03 tttttt
        track.writeVlq(0)
        track.write(byteArrayOf(0xFF.b, 0x51, 0x03, (TEMPO_US shr 16).b, (TEMPO_US shr 8).b, TEMPO_US.b))
        var last = 0L
        for (e in events.sortedBy { it.timeMs }) { // 안정 정렬: 같은 시각 이벤트의 수신 순서 유지
            val t = maxOf(e.timeMs, 0L)
            track.writeVlq(t - last)
            track.write(e.data) // running status 없이 매번 상태 바이트를 씀 (단순·유효)
            last = t
        }
        track.writeVlq(0)
        track.write(byteArrayOf(0xFF.b, 0x2F, 0x00)) // End of Track

        out.write("MThd".toByteArray())
        out.writeInt32(6)
        out.writeInt16(0) // format 0
        out.writeInt16(1) // 트랙 1개
        out.writeInt16(PPQ)
        out.write("MTrk".toByteArray())
        out.writeInt32(track.size())
        track.writeTo(out)
    }

    fun read(bytes: ByteArray): List<MidiEvent> {
        val r = Reader(bytes)
        require(r.str4() == "MThd") { "SMF 헤더 없음" }
        val headerLen = r.int32()
        r.int16() // format
        val tracks = r.int16()
        val division = r.int16()
        r.pos += headerLen - 6

        class Raw(val tick: Long, val order: Int, val data: ByteArray?, val tempo: Int)
        val raws = ArrayList<Raw>()
        var order = 0
        repeat(tracks) {
            while (r.str4() != "MTrk") r.pos += r.int32() // 모르는 청크 건너뜀
            val len = r.int32()
            val end = r.pos + len
            var tick = 0L
            var running = 0
            while (r.pos < end) {
                tick += r.vlq()
                var status = r.u8()
                when {
                    status == 0xFF -> {
                        val type = r.u8()
                        val l = r.vlq().toInt()
                        if (type == 0x51 && l == 3) {
                            raws += Raw(tick, order++, null, (r.u8() shl 16) or (r.u8() shl 8) or r.u8())
                        } else r.pos += l
                    }
                    status == 0xF0 || status == 0xF7 -> r.pos += r.vlq().toInt()
                    else -> {
                        if (status < 0x80) { r.pos--; status = running } else running = status
                        val n = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                        val msg = ByteArray(n + 1)
                        msg[0] = status.b
                        for (i in 1..n) msg[i] = r.u8().b
                        raws += Raw(tick, order++, msg, 0)
                    }
                }
            }
            r.pos = end
        }

        // tick → 시간 변환. 템포 이벤트를 만날 때마다 이후 구간의 tick 길이가 바뀐다.
        val smpte = division and 0x8000 != 0
        val smpteTickUs = if (smpte) {
            val fps = 256 - (division shr 8)
            1_000_000.0 / (fps * (division and 0xFF))
        } else 0.0
        var tempo = TEMPO_US
        var lastTick = 0L
        var us = 0.0
        val result = ArrayList<MidiEvent>()
        for (raw in raws.sortedWith(compareBy({ it.tick }, { it.order }))) {
            us += (raw.tick - lastTick) * if (smpte) smpteTickUs else tempo.toDouble() / division
            lastTick = raw.tick
            if (raw.data == null) tempo = raw.tempo
            else result += MidiEvent((us / 1000).toLong(), raw.data)
        }
        return result
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8() = b[pos++].toInt() and 0xFF
        fun int16() = (u8() shl 8) or u8()
        fun int32() = (int16() shl 16) or int16()
        fun str4() = String(b, pos, 4, Charsets.US_ASCII).also { pos += 4 }
        fun vlq(): Long {
            var v = 0L
            while (true) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
        }
    }

    private val Int.b get() = toByte()

    /** 가변 길이 수량: 7비트씩 나눠 상위 그룹부터, 마지막 바이트만 MSB=0 */
    private fun OutputStream.writeVlq(value: Long) {
        var v = value
        val stack = ArrayList<Int>()
        stack += (v and 0x7F).toInt()
        v = v shr 7
        while (v > 0) {
            stack += ((v and 0x7F) or 0x80).toInt()
            v = v shr 7
        }
        for (i in stack.indices.reversed()) write(stack[i])
    }

    private fun OutputStream.writeInt32(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    private fun OutputStream.writeInt16(v: Int) {
        write(v ushr 8); write(v)
    }
}
