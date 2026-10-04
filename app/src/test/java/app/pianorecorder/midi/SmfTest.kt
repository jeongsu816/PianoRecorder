package app.pianorecorder.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class SmfTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun writeThenReadKeepsTimesAndMessages() {
        val events = listOf(
            MidiEvent(0, bytes(0x90, 60, 80)),
            MidiEvent(250, bytes(0xB0, 64, 127)),
            MidiEvent(250, bytes(0x90, 64, 70)), // 같은 시각: 순서 유지
            MidiEvent(1_000, bytes(0x80, 60, 90)),
            MidiEvent(200_000, bytes(0x80, 64, 0)), // VLQ 여러 바이트
        )
        val out = ByteArrayOutputStream()
        Smf.write(events, out)
        val read = Smf.read(out.toByteArray())

        assertEquals(events.map { it.timeMs }, read.map { it.timeMs })
        events.zip(read).forEach { (a, b) -> assertArrayEquals(a.data, b.data) }
    }

    @Test
    fun negativeTimesAreClampedToZero() {
        val out = ByteArrayOutputStream()
        Smf.write(listOf(MidiEvent(-30, bytes(0x90, 60, 1)), MidiEvent(10, bytes(0x80, 60, 0))), out)
        assertEquals(listOf(0L, 10L), Smf.read(out.toByteArray()).map { it.timeMs })
    }

    @Test
    fun headerIsFormat0WithMillisecondTicks() {
        val out = ByteArrayOutputStream()
        Smf.write(emptyList(), out)
        val b = out.toByteArray()
        assertEquals("MThd", String(b, 0, 4))
        assertEquals(0, b[9].toInt())   // format 0
        assertEquals(1, b[11].toInt())  // 1 track
        assertEquals(Smf.PPQ, (b[12].toInt() and 0xFF shl 8) or (b[13].toInt() and 0xFF))
    }

    /** 다른 프로그램이 만든 파일: running status + 템포 변경 + Format 1 */
    @Test
    fun readsRunningStatusAndTempoChangesAcrossTracks() {
        fun chunk(id: String, body: ByteArray) =
            id.toByteArray() + bytes(0, 0, body.size shr 8, body.size and 0xFF) + body
        val header = chunk("MThd", bytes(0, 1, 0, 2, 0, 96)) // format 1, 2 tracks, 96 ppq
        // 트랙 0: 0tick 템포 1,000,000µs/4분음표, 96tick에 500,000µs/4분음표로 변경
        val t0 = chunk("MTrk", bytes(
            0x00, 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40,
            0x60, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0xFF, 0x2F, 0x00))
        // 트랙 1: 0tick NoteOn, 96tick(running status) NoteOn vel0, 96tick 뒤 또 하나
        val t1 = chunk("MTrk", bytes(
            0x00, 0x90, 60, 100,
            0x60, 60, 0,
            0x60, 62, 100,
            0x00, 0xFF, 0x2F, 0x00))
        val read = Smf.read(header + t0 + t1)
        // 0ms, 96tick@1s/qn = 1000ms, 그 뒤 96tick@0.5s/qn = +500ms
        assertEquals(listOf(0L, 1000L, 1500L), read.map { it.timeMs })
        assertArrayEquals(bytes(0x90, 60, 0), read[1].data)
        assertArrayEquals(bytes(0x90, 62, 100), read[2].data)
    }
}
