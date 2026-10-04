package app.pianorecorder.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg chunks: IntArray): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        val p = MidiParser { m, _ -> out += m.map { it.toInt() and 0xFF } }
        for (c in chunks) {
            val b = ByteArray(c.size) { c[it].toByte() }
            p.feed(b, 0, b.size, 0)
        }
        return out
    }

    @Test
    fun splitsBatchedMessages() {
        assertEquals(
            listOf(listOf(0x90, 60, 80), listOf(0x80, 60, 0)),
            parse(intArrayOf(0x90, 60, 80, 0x80, 60, 0)),
        )
    }

    @Test
    fun expandsRunningStatus() {
        assertEquals(
            listOf(listOf(0xB0, 64, 10), listOf(0xB0, 64, 50), listOf(0xB0, 64, 127)),
            parse(intArrayOf(0xB0, 64, 10, 64, 50, 64, 127)),
        )
    }

    @Test
    fun handlesMessageSplitAcrossCalls() {
        assertEquals(listOf(listOf(0x90, 60, 80)), parse(intArrayOf(0x90), intArrayOf(60), intArrayOf(80)))
    }

    @Test
    fun realtimeInsideMessageDoesNotBreakIt() {
        assertEquals(
            listOf(listOf(0xFE), listOf(0x90, 60, 80)),
            parse(intArrayOf(0x90, 60, 0xFE, 80)),
        )
    }

    @Test
    fun skipsSysEx() {
        assertEquals(
            listOf(listOf(0xC0, 5)),
            parse(intArrayOf(0xF0, 0x41, 0x10, 0x42, 0xF7, 0xC0, 5)),
        )
    }
}
