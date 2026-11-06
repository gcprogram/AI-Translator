package app.aitranslator.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8AssemblerTest {
    private fun feed(text: String, chunkSizes: List<Int>): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val assembler = Utf8Assembler()
        val out = StringBuilder()
        var pos = 0
        var i = 0
        while (pos < bytes.size) {
            val size = chunkSizes[i++ % chunkSizes.size].coerceAtMost(bytes.size - pos)
            out.append(assembler.push(bytes.copyOfRange(pos, pos + size)))
            pos += size
        }
        return out.append(assembler.flush()).toString()
    }

    @Test
    fun asciiPassesThroughImmediately() {
        val assembler = Utf8Assembler()
        assertEquals("Hallo", assembler.push("Hallo".toByteArray()))
        assertEquals("", assembler.flush())
    }

    @Test
    fun umlautSplitAcrossTokensIsHeldBackUntilComplete() {
        val bytes = "ü".toByteArray(Charsets.UTF_8) // zwei Bytes
        val assembler = Utf8Assembler()
        assertEquals("", assembler.push(byteArrayOf(bytes[0])))
        assertEquals("ü", assembler.push(byteArrayOf(bytes[1])))
    }

    @Test
    fun anySplitOfAnyTextRebuildsTheSameText() {
        val text = "Größe, naïve Café, Привет, ελληνικά, 日本語のテキスト, 😀 und ein Ende. Ü"
        for (sizes in listOf(listOf(1), listOf(2), listOf(3), listOf(1, 2, 3), listOf(5, 1), listOf(7))) {
            assertEquals("Aufteilung $sizes", text, feed(text, sizes))
        }
    }

    @Test
    fun fourByteEmojiNeedsAllFourBytes() {
        val bytes = "😀".toByteArray(Charsets.UTF_8)
        val assembler = Utf8Assembler()
        assertEquals("", assembler.push(bytes.copyOfRange(0, 1)))
        assertEquals("", assembler.push(bytes.copyOfRange(1, 3)))
        assertEquals("😀", assembler.push(bytes.copyOfRange(3, 4)))
    }

    @Test
    fun truncatedCharacterAtTheEndIsFlushedNotLost() {
        val assembler = Utf8Assembler()
        assertEquals("a", assembler.push(byteArrayOf('a'.code.toByte(), "ü".toByteArray()[0])))
        assertTrue(assembler.flush().isNotEmpty()) // abgeschnittenes Zeichen kommt als Ersatzzeichen, nicht als Absturz
    }

    @Test
    fun strayContinuationBytesDoNotBlock() {
        val assembler = Utf8Assembler()
        assertTrue(assembler.push(byteArrayOf(0x80.toByte(), 0x80.toByte())).isNotEmpty())
    }
}

class StopMatcherTest {
    private fun run(stops: List<String>, vararg pieces: String): Triple<String, Boolean, StopMatcher> {
        val matcher = StopMatcher(stops)
        val out = StringBuilder()
        for (piece in pieces) out.append(matcher.feed(piece))
        out.append(matcher.flush())
        return Triple(out.toString(), matcher.stopped, matcher)
    }

    @Test
    fun textWithoutStopIsPassedThroughCompletely() {
        val (text, stopped) = run(listOf("<end_of_turn>"), "Hallo ", "Welt.")
        assertEquals("Hallo Welt.", text)
        assertFalse(stopped)
    }

    @Test
    fun stopInOnePieceCutsTheText() {
        val (text, stopped) = run(listOf("<end_of_turn>"), "Hallo<end_of_turn>Rest")
        assertEquals("Hallo", text)
        assertTrue(stopped)
    }

    @Test
    fun stopSplitAcrossPiecesIsNeverEmittedPartly() {
        val (text, stopped) = run(listOf("<end_of_turn>"), "Hallo <end", "_of_", "turn>nachher")
        assertEquals("Hallo ", text)
        assertTrue(stopped)
    }

    @Test
    fun possibleStopStartIsReleasedWhenItTurnsOutHarmless() {
        val (text, stopped) = run(listOf("<end_of_turn>"), "a <b", "> c")
        assertEquals("a <b> c", text)
        assertFalse(stopped)
    }

    @Test
    fun heldBackTextIsFlushedAtTheEnd() {
        val (text, _) = run(listOf("<end_of_turn>"), "Ende <end_of")
        assertEquals("Ende <end_of", text)
    }

    @Test
    fun nothingIsEmittedAfterAStop() {
        val matcher = StopMatcher(listOf("STOP"))
        assertEquals("x", matcher.feed("xSTOPy"))
        assertEquals("", matcher.feed("mehr"))
        assertEquals("", matcher.flush())
    }

    @Test
    fun earliestOfSeveralStopsWins() {
        val (text, stopped) = run(listOf("BBB", "AA"), "xxAAyyBBB")
        assertEquals("xx", text)
        assertTrue(stopped)
    }

    @Test
    fun noStopsMeansNoHoldBack() {
        val matcher = StopMatcher(emptyList())
        assertEquals("<end_of_turn>", matcher.feed("<end_of_turn>"))
    }
}
