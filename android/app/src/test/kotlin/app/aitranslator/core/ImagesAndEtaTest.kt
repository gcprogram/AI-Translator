package app.aitranslator.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Minimales PNG-Gerüst: Signatur + IHDR mit Breite/Höhe; reicht für Formaterkennung und Größe. */
internal fun fakePng(width: Int, height: Int, filler: Int = 0): ByteArray {
    fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
        be(13) + "IHDR".toByteArray() + be(width) + be(height) + ByteArray(5) + byteArrayOf(filler.toByte())
}

class EtaTest {
    @Test
    fun noEstimateWithoutProgress() {
        assertNull(remainingMillis(10_000, 0, 1000))
        assertNull(remainingMillis(0, 100, 1000))
        assertNull(etaText(10_000, 0, 1000))
    }

    @Test
    fun remainingScalesWithCharactersNotSections() {
        // 100 s für 250 von 1000 Zeichen -> 300 s für die restlichen 750
        assertEquals(300_000L, remainingMillis(100_000, 250, 1000))
        assertEquals(0L, remainingMillis(100_000, 1000, 1000))
        assertNull(etaText(100_000, 1000, 1000)) // fertig: nichts mehr zu schätzen
    }

    @Test
    fun durationIsReadable() {
        assertEquals("unter 1 Min.", formatDuration(20_000))
        assertEquals("1 Min.", formatDuration(45_000))
        assertEquals("41 Min.", formatDuration(41 * 60_000L))
        assertEquals("1 Std. 5 Min.", formatDuration(65 * 60_000L))
        assertEquals("3 Std. 0 Min.", formatDuration(180 * 60_000L))
    }

    @Test
    fun etaTextShowsRemainingAndFinishTime() {
        val text = etaText(elapsedMs = 600_000, doneChars = 1000, totalChars = 5000, now = LocalTime.of(13, 0))
        assertEquals("noch ca. 40 Min. (fertig gegen 13:40)", text)
    }

    @Test
    fun lastMinuteReadsNaturally() {
        assertEquals("noch unter 1 Min. (fertig gegen 13:00)", etaText(10_000, 900, 1000, LocalTime.of(13, 0)))
    }

    @Test
    fun finishTimeWrapsPastMidnight() {
        assertTrue(etaText(3_600_000, 1, 2, LocalTime.of(23, 30))!!.endsWith("(fertig gegen 00:30)"))
    }
}

class ImagePlaceholderTest {
    @Test
    fun tokensRoundTrip() {
        assertEquals("[[Bild 7]]", imageToken(7))
        assertEquals(listOf(1, 12, 1), imageIds("a [[Bild 1]] b\n\n[[Bild 12]]\n\n[[Bild 1]]"))
        assertEquals(12, imageIdOfLine("  [[Bild 12]] "))
        assertNull(imageIdOfLine("Text [[Bild 12]]"))
    }

    @Test
    fun extractTakesImageParagraphsOutAndRemembersWhereTheyWere() {
        val split = extractImages("[[Bild 1]]\n\nErster.\n\nZweiter.\n\n[[Bild 2]]\n\nDritter.\n\n[[Bild 3]]")
        assertEquals("Erster.\n\nZweiter.\n\nDritter.", split.text)
        assertEquals(3, split.textParagraphs)
        assertEquals(listOf(ImageAnchor(0, 1), ImageAnchor(2, 2), ImageAnchor(3, 3)), split.anchors)
    }

    @Test
    fun consecutiveImagesInOneParagraphStayInOrder() {
        val split = extractImages("Text.\n\n[[Bild 4]]\n[[Bild 5]]")
        assertEquals(listOf(ImageAnchor(1, 4), ImageAnchor(1, 5)), split.anchors)
    }

    @Test
    fun reinsertPutsImagesBackAtTheSameParagraphWhenCountsMatch() {
        val source = "[[Bild 1]]\n\nOne.\n\nTwo.\n\n[[Bild 2]]\n\nThree."
        val split = extractImages(source)
        assertEquals("[[Bild 1]]\n\nEins.\n\nZwei.\n\n[[Bild 2]]\n\nDrei.", reinsertImages("Eins.\n\nZwei.\n\nDrei.", split))
    }

    @Test
    fun reinsertScalesProportionallyWhenModelChangedParagraphCount() {
        // Bild nach 5 von 10 Absätzen; die Übersetzung hat nur 4 -> Bild nach Absatz 2
        val source = (1..5).joinToString("\n\n") { "A$it." } + "\n\n[[Bild 9]]\n\n" + (6..10).joinToString("\n\n") { "A$it." }
        val merged = "B1.\n\nB2.\n\nB3.\n\nB4."
        assertEquals("B1.\n\nB2.\n\n[[Bild 9]]\n\nB3.\n\nB4.", reinsertImages(merged, extractImages(source)))
    }

    @Test
    fun imageOnlyChunkAndNoImagesAreHandled() {
        val split = extractImages("[[Bild 1]]\n\n[[Bild 2]]")
        assertEquals("", split.text)
        assertEquals("[[Bild 1]]\n\n[[Bild 2]]", reinsertImages("", split))
        assertEquals("Nur Text.", reinsertImages("Nur Text.", extractImages("Only text.")))
    }

    @Test
    fun removeTokensLeavesNoGap() {
        assertEquals("A.\n\nB.", removeImageTokens("A.\n\n[[Bild 1]]\n\nB."))
    }

    @Test
    fun collectorReusesIdsForIdenticalImages() {
        val collector = ImageCollector()
        val a = collector.add(fakePng(10, 10, 1), "image/png")
        val b = collector.add(fakePng(10, 10, 2), "image/png")
        assertEquals(a, collector.add(fakePng(10, 10, 1), "image/png")) // Zierlinie o. ä.: dieselbe Nummer
        assertTrue(a != b)
        assertEquals(2, collector.images.size)
    }

    @Test
    fun blocksRecognizeImageLinesAndTextExportsSkipThem() {
        val blocks = parseBlocks("Vorher.\n[[Bild 3]]\nNachher.\n\n[[Bild 4]]")
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.IMAGE, BlockKind.PARAGRAPH, BlockKind.IMAGE),
            blocks.map { it.kind },
        )
        assertEquals(listOf(3, 4), blocks.filter { it.kind == BlockKind.IMAGE }.map { it.imageId })
        assertFalse("Bild" in toPlainText(blocks))
        assertFalse("Bild" in toMarkdown(blocks))
        assertTrue("Vorher." in toPlainText(blocks) && "Nachher." in toPlainText(blocks))
    }

    @Test
    fun contextIgnoresImagePlaceholders() {
        val context = buildContext("A.\n\n[[Bild 1]]\n\nB.", "X.\n\n[[Bild 1]]\n\nY.")
        assertEquals("A.\n\nB." to "X.\n\nY.", context)
    }
}

class ImageFormatTest {
    @Test
    fun sniffsMimeFromContent() {
        assertEquals("image/png", sniffMime(fakePng(1, 1)))
        assertEquals("image/gif", sniffMime("GIF89a".toByteArray() + ByteArray(8)))
        assertEquals("image/jpeg", sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)))
        assertEquals("image/svg+xml", sniffMime("<?xml version=\"1.0\"?><svg xmlns=\"x\"/>".toByteArray()))
        assertNull(sniffMime("irgendwas".toByteArray()))
        assertEquals("png", extensionForMime("image/png"))
        assertEquals("jpg", extensionForMime("image/jpeg"))
        assertEquals("image/jpeg", mimeForExtension("JPG"))
    }

    @Test
    fun sniffsSizeOfPngGifBmpAndJpeg() {
        assertEquals(640 to 480, sniffImageSize(fakePng(640, 480)))
        val gif = "GIF89a".toByteArray() + byteArrayOf(0x20, 0x01, 0xC8.toByte(), 0x00) + ByteArray(4) // 288 x 200
        assertEquals(288 to 200, sniffImageSize(gif))
        val bmp = ByteArray(30).also { it[0] = 'B'.code.toByte(); it[1] = 'M'.code.toByte(); it[18] = 100; it[22] = 50 }
        assertEquals(100 to 50, sniffImageSize(bmp))
        // JPEG: SOI, APP0 (Länge 16), dann SOF0 mit Höhe 300 (0x012C) und Breite 400 (0x0190)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x01, 0x2C, 0x01, 0x90.toByte(), 0x03) + ByteArray(12)
        assertEquals(400 to 300, sniffImageSize(jpeg))
        assertNull(sniffImageSize("kein Bild".toByteArray()))
        assertNull(sniffImageSize(fakePng(0, 0)))
    }
}

class PipelineImagesTest {
    private fun config() = Config(model = "google.translategemma-4b-it", langSource = "en", langTarget = "de").sanitized()

    @Test
    fun modelNeverSeesPlaceholdersAndImagesComeBackAtTheSamePlace() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Erster Absatz.\n\nZweiter Absatz.", "stop") }
        val chunk = Chunk(1, "First paragraph.\n\n[[Bild 1]]\n\nSecond paragraph.\n\n[[Bild 2]]")
        val result = TranslationPipeline(backend, config()).translateChunk(chunk, emptyMap())
        assertFalse(backend.calls.any { "Bild" in it.prompt })
        assertEquals("Erster Absatz.\n\n[[Bild 1]]\n\nZweiter Absatz.\n\n[[Bild 2]]", result.translation)
        assertEquals(chunk.text, result.source) // Original unverändert
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun chunkWithOnlyImagesNeedsNoModelCall() = runTest {
        val backend = FakeBackend { _, _ -> error("darf nicht aufgerufen werden") }
        val result = TranslationPipeline(backend, config()).translateChunk(Chunk(1, "[[Bild 1]]\n\n[[Bild 2]]"), emptyMap())
        assertEquals("[[Bild 1]]\n\n[[Bild 2]]", result.translation)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun headingRestoredAroundImages() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Der Anfang\n\nEs war einmal.", "stop") }
        val result = TranslationPipeline(backend, config())
            .translateChunk(Chunk(1, "# The Start\n\n[[Bild 1]]\n\nOnce upon a time."), emptyMap())
        assertEquals("# Der Anfang\n\n[[Bild 1]]\n\nEs war einmal.", result.translation)
    }

    @Test
    fun contextFromPreviousChunkDoesNotContainPlaceholders() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Übersetzt.", "stop") }
        val chunks = listOf(Chunk(1, "One.\n\n[[Bild 1]]"), Chunk(2, "Two."))
        TranslationPipeline(backend, config()).translate(chunks).collectAll()
        assertFalse(backend.calls.any { "Bild" in it.prompt })
        assertTrue("One." in backend.calls[1].prompt) // Kontext wurde trotzdem mitgeschickt
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectAll(): List<T> {
    val items = mutableListOf<T>()
    collect { items += it }
    return items
}
