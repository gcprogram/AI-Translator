package app.aitranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

class ChunkingTest {
    @Test
    fun noTextLostAndSizeRespected() {
        val text = (0 until 30).joinToString("\n\n") { "Satz eins von Absatz $it. Satz zwei! Satz drei? ".repeat(8) }
        val chunks = splitIntoChunks(text, 700)
        assertTrue(chunks.size > 3)
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
        assertTrue(chunks.all { it.text.length <= 700 })
        assertEquals((1..chunks.size).toList(), chunks.map { it.index })
    }

    @Test
    fun overlongParagraphIsSplitAtSentencesNotTruncated() {
        val text = (0 until 200).joinToString(" ") { "Das ist Satz Nummer $it." }
        val chunks = splitIntoChunks(text, 500)
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
        assertTrue(chunks.all { it.text.endsWith(".") })
    }

    @Test
    fun singleOverlongSentenceIsHardSplit() {
        val text = "wort ".repeat(500)
        val chunks = splitIntoChunks(text, 300)
        assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
        assertTrue(chunks.all { it.text.length <= 300 })
    }

    @Test
    fun cjkWithoutSpaces() {
        val text = "这是一个句子。".repeat(300)
        assertEquals(text, splitIntoChunks(text, 200).joinToString("") { it.text })
    }

    @Test
    fun smallLimitAndEmpty() {
        assertEquals(emptyList<Chunk>(), splitIntoChunks("", 100))
        assertEquals(1, splitIntoChunks("Hallo Welt. Noch ein Satz.", 50).size)
        val text = "Eins. Zwei. Drei. Vier. Fünf."
        assertEquals(words(text), words(splitIntoChunks(text, 8).joinToString(" ") { it.text }))
        assertThrows(IllegalArgumentException::class.java) { splitIntoChunks("x", 0) }
    }

    @Test
    fun headingStaysWithFollowingParagraph() {
        val body = "Ein Absatz mit Text. ".repeat(5) // ~105 Zeichen
        val text = "$body\n\n## Kapitel\n\n$body"
        for (limit in listOf(120, 130, 220)) {
            val chunks = splitIntoChunks(text, limit)
            assertEquals(words(text), words(chunks.joinToString(" ") { it.text }))
            assertFalse("limit $limit", chunks.any { it.text.endsWith("Kapitel") })
        }
    }

    @Test
    fun chapterStartsNewChunkOnceChunkIsFilled() {
        val body = "Satz eins. Satz zwei. Satz drei.\n\n".repeat(10)
        val chunks = splitIntoChunks("# Eins\n\n$body# Zwei\n\n$body", 1000)
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].text.startsWith("# Zwei"))
    }

    @Test
    fun restoreHeadings() {
        val source = "# Titel\n\nText.\n\n## Unter\n\nMehr."
        assertEquals("# Titel\n\nText.\n\n## Unter\n\nMehr.", restoreHeadings(source, "Titel\n\nText.\n\nUnter\n\nMehr."))
        val broken = "Titel Text.\n\nMehr." // Absatzzahl passt nicht: unverändert lassen
        assertEquals(broken, restoreHeadings(source, broken))
    }
}

class ConfigAndLanguageTest {
    @Test
    fun sanitizedClampsAndFillsDefaults() {
        val config = Config(ip = "  ", port = "", langTarget = "de-DE", maxChunkChars = 10, translationPrompt = " ").sanitized()
        assertEquals("1234", config.port)
        assertEquals("de", config.langTarget)
        assertEquals(MIN_CHUNK_CHARS, config.maxChunkChars)
        assertEquals(DEFAULT_PROMPT, config.translationPrompt)
        assertEquals(MAX_CHUNK_CHARS, Config(maxChunkChars = 10_000_000).sanitized().maxChunkChars)
        assertTrue(config.ip.isNotBlank())
    }

    @Test
    fun backendDecidesWhichModelCountsAndWhetherSetupIsComplete() {
        val lmStudio = Config(model = "google.translategemma-4b-it")
        assertTrue(lmStudio.isConfigured && lmStudio.isTranslateGemma && !lmStudio.isOnDevice)
        assertFalse(Config().isConfigured) // nichts gewählt

        val device = Config(backend = BACKEND_DEVICE, model = "qwen", deviceModelName = "google.translategemma-4b-it.Q4_K_S.gguf")
        assertFalse(device.isConfigured) // Datei fehlt noch
        val ready = device.copy(deviceModelUri = "content://x/model")
        assertTrue(ready.isConfigured && ready.isOnDevice)
        assertTrue(ready.isTranslateGemma) // zählt der Name der Datei, nicht das LM-Studio-Modell 'qwen'
        assertFalse(ready.copy(deviceModelName = "irgendwas.gguf").isTranslateGemma)
        assertEquals(BACKEND_LMSTUDIO, Config(backend = "unsinn").sanitized().backend)
    }

    @Test
    fun translategemmaIsDetectedCaseInsensitive() {
        assertTrue(Config(model = "google.TranslateGemma-4b-it").isTranslateGemma)
        assertFalse(Config(model = "qwen").isTranslateGemma)
    }

    @Test
    fun languageHelpers() {
        assertEquals("German", Languages.name("de-DE"))
        assertEquals("xx", Languages.name("xx")) // kein Platzhalter-Text
        assertEquals("de", Languages.codeFromDisplay("German (de)"))
        assertEquals("de", Languages.codeFromDisplay("DE"))
        assertEquals("German (de)", Languages.displayName("de_DE"))
        assertTrue("German (de)" in Languages.displayList)
    }
}

class PromptsTest {
    @Test
    fun gemmaPromptUsesRealLanguageNames() {
        val prompt = gemmaPrompt("Hello.", "en", "de-DE")
        assertTrue("English (en) to German (de) translator" in prompt)
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
        assertFalse("exactly as given" in prompt)
    }

    @Test
    fun gemmaPromptWithGlossaryAndContext() {
        val prompt = gemmaPrompt("Next.", "en", "de", mapOf("Polyjuice Potion" to "Vielsafttrank"), "Prev." to "Vorher.")
        assertTrue("Polyjuice Potion = Vielsafttrank" in prompt)
        // Vorgänger als bereits beantworteter Zug, danach die eigentliche Anfrage
        assertEquals(2, Regex("<start_of_turn>user").findAll(prompt).count())
        assertTrue(prompt.indexOf("Prev.") < prompt.indexOf("<start_of_turn>model\nVorher.<end_of_turn>"))
        assertTrue(prompt.indexOf("Vorher.") < prompt.indexOf("Next."))
        assertTrue(prompt.endsWith("Next.<end_of_turn>\n<start_of_turn>model\n"))
    }

    @Test
    fun chatMessagesCarryContextAsAnsweredTurn() {
        val messages = chatMessages("sys", "neu", "alt" to "old")
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.role })
        assertEquals("neu", messages.last().content)
        val system = chatSystemPrompt("From {source} to {target}.", "en", "de", mapOf("Orc" to "Ork"))
        assertTrue("From English to German." in system && "- Orc = Ork" in system)
    }
}

class GlossaryTest {
    @Test
    fun parseKeepsCaseAndStripsBullets() {
        val g = parseGlossary("- NASA: NASA\n1. **iPhone**: iPhone\nkein Eintrag\nLeer:\n")
        assertEquals(mapOf("NASA" to "NASA", "iPhone" to "iPhone"), g)
    }

    @Test
    fun mergeExistingWinsCaseInsensitive() {
        assertEquals(mapOf("Orc" to "Ork", "Elf" to "Elfe"), mergeGlossary(mapOf("Orc" to "Ork"), mapOf("orc" to "Orks", "Elf" to "Elfe")))
    }

    @Test
    fun relevantTermsMatchPluralAndCaseButNotInsideWords() {
        val glossary = mapOf("Potion" to "Trank", "Orc" to "Ork", "Elf" to "Elfe")
        assertEquals(mapOf("Potion" to "Trank", "Orc" to "Ork"), relevantTerms(glossary, "Two potions and an ORC; the shelf."))
    }

    @Test
    fun missingTermsToleratesGermanInflection() {
        val terms = mapOf("Polyjuice Potion" to "Vielsafttrank", "Orc" to "Ork")
        assertEquals(emptyList<String>(), missingTerms(terms, "Er trank den Rest des Vielsafttranks. Ork."))
        assertEquals(listOf("Polyjuice Potion"), missingTerms(terms, "Er trank den Vielfruchttrank. Ork."))
    }

    @Test
    fun formatIsSortedAndRoundtrips() {
        val g = mapOf("zebra" to "Zebra", "Apfel" to "apple")
        assertEquals("Apfel: apple\nzebra: Zebra\n", formatGlossary(g))
        assertEquals(g, parseGlossary(formatGlossary(g)))
    }
}

class HeadingDetectionTest {
    @Test
    fun chapterWordsNumbersAndSubtitle() {
        val text = "Prolog\n\nEs war einmal.\n\nKapitel 1\n\nDer Anfang\n\nText hier.\n\n2\n\nNoch ein Absatz."
        assertEquals("# Prolog\n\nEs war einmal.\n\n# Kapitel 1 – Der Anfang\n\nText hier.\n\n# 2\n\nNoch ein Absatz.", markHeadings(text))
    }

    @Test
    fun partsMakeChaptersSecondLevel() {
        assertEquals("# Teil Eins\n\n## Kapitel 1\n\nText.\n\n## Kapitel 2\n\nText.", markHeadings("Teil Eins\n\nKapitel 1\n\nText.\n\nKapitel 2\n\nText."))
    }

    @Test
    fun existingMarkdownIsLeftAlone() {
        val text = "# Titel\n\nKapitel 1\n\nText."
        assertEquals(text, markHeadings(text))
    }

    @Test
    fun runningTextIsNotAHeading() {
        val text = "Er sagte nichts.\n\nSie ging nach Hause und schlief ein, weil es spät war und niemand mehr da war"
        assertEquals(text, markHeadings(text))
    }

    @Test
    fun allCapsLineWithSeveralWordsIsAHeading() {
        assertEquals("Text.\n\n# DER GROSSE ANFANG\n\nMehr.", markHeadings("Text.\n\nDER GROSSE ANFANG\n\nMehr."))
    }

    private val page = 842f

    @Test
    fun pdfFontSizesAndPageNumbers() {
        val lines = listOf(
            PdfLine("Das Grosse Buch", 24f, 100f, page, 1),
            PdfLine("Ein normaler Absatz im Fliesstext des Buches.", 11f, 150f, page, 1),
            PdfLine("Abschnitt Zwei", 16f, 180f, page, 1),
            PdfLine("Noch mehr Fliesstext hier drin.", 11f, 210f, page, 1),
            PdfLine("7", 11f, 820f, page, 1),
        )
        assertEquals(
            "# Das Grosse Buch\n\nEin normaler Absatz im Fliesstext des Buches.\n\n## Abschnitt Zwei\n\nNoch mehr Fliesstext hier drin.",
            joinParagraphs(markPdfHeadings(groupPdfLines(lines))),
        )
    }

    @Test
    fun pdfLinesOfOneParagraphAreJoinedAndGapsSplitParagraphs() {
        val lines = listOf(
            PdfLine("Der Absatz beginnt hier", 11f, 100f, page, 1),
            PdfLine("und geht weiter.", 11f, 113f, page, 1),
            PdfLine("Neuer Absatz nach Abstand.", 11f, 160f, page, 1),
        )
        assertEquals(
            listOf("Der Absatz beginnt hier und geht weiter.", "Neuer Absatz nach Abstand."),
            groupPdfLines(lines).map { it.text },
        )
    }

    @Test
    fun pdfPageBreakIsJoined() {
        val lines = listOf(
            PdfLine("Der Satz beginnt hier und", 11f, 700f, page, 1),
            PdfLine("endet auf der naechsten Seite.", 11f, 100f, page, 2),
        )
        assertEquals("Der Satz beginnt hier und endet auf der naechsten Seite.", joinParagraphs(markPdfHeadings(groupPdfLines(lines))))
    }

    @Test
    fun joinDoesNotMergeIntoHeadings() {
        assertEquals("# Titel\n\nkleingeschrieben geht weiter", joinParagraphs(listOf("# Titel", "kleingeschrieben geht weiter")))
    }
}

class BlocksTest {
    private val content = "# Das Buch\n\nVorwort mit *Kursiv*.\n\n# Kapitel 1\n\nErster **fetter** Absatz.\nZweite Zeile.\n\n" +
        "## Unterkapitel\n\nText.\n\n# Kapitel 2\n\nSchluss & <Ende>."

    @Test
    fun parseBlocksSplitsGluedHeadingAndKeepsLineBreaks() {
        assertEquals(
            listOf(
                Block(BlockKind.HEADING, "Titel", 1),
                Block(BlockKind.PARAGRAPH, "Text A\nText B"),
                Block(BlockKind.PARAGRAPH, "Ende"),
            ),
            parseBlocks("# Titel\nText A\nText B\n\n\n\nEnde"),
        )
    }

    @Test
    fun inlineRunsAndStripping() {
        assertEquals(
            listOf(Run("Ein ", false, false), Run("fetter", true, false), Run(" und ", false, false), Run("kursiver", false, true), Run(" Satz", false, false)),
            inlineRuns("Ein **fetter** und *kursiver* Satz"),
        )
        assertEquals("5 * 3 * 2", stripInline("5 * 3 * 2")) // Rechenzeichen sind keine Auszeichnung
    }

    @Test
    fun chapterLevelIgnoresSingleTitle() {
        assertEquals(1, chapterLevel(parseBlocks(content)))
        assertEquals(2, chapterLevel(parseBlocks("# Titel\n\n## A\n\nx\n\n## B\n\ny")))
        assertEquals(0, chapterLevel(parseBlocks("nur Text")))
    }

    @Test
    fun chaptersCollectTextBeforeFirstHeadingUnderDocumentTitle() {
        val chapters = splitChapters(parseBlocks("Vorspann.\n\n# Eins\n\nA\n\n# Zwei\n\nB"), "Buch")
        assertEquals(listOf("Buch", "Eins", "Zwei"), chapters.map { it.title })
        assertNull(splitChapters(parseBlocks("nur Text"), "Buch").singleOrNull()?.blocks?.firstOrNull { it.kind == BlockKind.HEADING })
    }

    @Test
    fun plainTextHasNoMarkupAndMarkdownKeepsIt() {
        val blocks = parseBlocks(content)
        val txt = toPlainText(blocks)
        assertFalse("#" in txt || "*" in txt)
        assertTrue("Erster fetter Absatz.\nZweite Zeile." in txt)
        assertTrue("## Unterkapitel" in toMarkdown(blocks))
    }
}
