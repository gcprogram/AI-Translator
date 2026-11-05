package app.aitranslator.core

/** Überschriften ohne Auszeichnung (reiner Text, PDF, DOCX ohne Formatvorlagen) erkennen. */
private const val PART_WORDS = "part|book|teil|buch|partie|livre|parte|libro"
private const val CHAPTER_WORDS = "chapter|kapitel|chapitre|cap[ií]tulo|capitolo|prologue?|prolog|epilogue?|epilog|" +
    "pr[oó]logo|ep[ií]logo|prelude|interlude|appendix|anhang|preface|vorwort|nachwort|" +
    "foreword|afterword|introduction|einleitung|conclusion|schlusswort|contents|inhalt"
private const val WORD_END = "(?![\\p{L}\\p{N}_])" // \b ist auf der JVM und auf Android unterschiedlich definiert
private val PART_RE = Regex("^(?:$PART_WORDS)$WORD_END.{0,60}$", RegexOption.IGNORE_CASE)
private val CHAPTER_RE = Regex("^(?:$CHAPTER_WORDS)$WORD_END.{0,60}$", RegexOption.IGNORE_CASE)
private val SPACES = Regex("$WS+")
private val NUMBER_RE = Regex("""^(?:\d{1,3}|[IVXLC]{1,7})[.)]?$""")
private const val MAX_HEADING_CHARS = 80
private const val MAX_SUBTITLE_CHARS = 60
private const val TERMINAL = ".!?…:;»«\"”’)。！？」』"

private fun endsTerminal(text: String): Boolean = text.lastOrNull()?.let { it in TERMINAL } ?: false

fun looksLikeHeading(text: String): Boolean =
    text.length <= MAX_HEADING_CHARS && '\n' !in text && !endsTerminal(text)

/** Python-Semantik von str.isupper(): mindestens ein Buchstabe mit Groß-/Kleinschreibung, keiner davon klein. */
private fun isAllUpper(text: String): Boolean {
    val cased = text.filter { it.isUpperCase() || it.isLowerCase() }
    return cased.isNotEmpty() && cased.none { it.isLowerCase() }
}

private fun isSubtitle(text: String): Boolean =
    text.isNotEmpty() && text.length <= MAX_SUBTITLE_CHARS && '\n' !in text && text[0].isUpperCase() &&
        text.split(SPACES).size <= 8 && !endsTerminal(text)

/**
 * Erkennt Kapitelüberschriften im Text ('Kapitel 3', 'CHAPTER ONE', '12', 'DER ANFANG') und macht sie zu
 * Markdown ('# Kapitel 3'). Enthält der Text schon Überschriften (Formatvorlagen, Schriftgröße, .md),
 * bleibt er unverändert.
 */
fun markHeadings(text: String): String {
    val paragraphs = text.split("\n\n")
    if (paragraphs.any { headingLevel(it) > 0 }) return text
    val kinds = mutableMapOf<Int, String>()
    paragraphs.map { it.trim() }.forEachIndexed { i, paragraph ->
        if (!looksLikeHeading(paragraph)) return@forEachIndexed
        when {
            PART_RE.matches(paragraph) -> kinds[i] = "part"
            CHAPTER_RE.matches(paragraph) || NUMBER_RE.matches(paragraph) -> kinds[i] = "chapter"
            isAllUpper(paragraph) && paragraph.split(SPACES).size >= 2 && paragraph.count { it.isLetter() } >= 3 ->
                kinds[i] = "chapter"
        }
    }
    if (kinds.isEmpty()) return text
    val chapterLevel = if ("part" in kinds.values) 2 else 1
    val out = mutableListOf<String>()
    var merged = -1
    paragraphs.forEachIndexed { i, original ->
        if (i == merged) return@forEachIndexed
        var paragraph = original
        if (i in kinds) {
            var title = original.trim()
            val following = paragraphs.getOrNull(i + 1)?.trim().orEmpty()
            if ((i + 1) !in kinds && isSubtitle(following)) {
                title = "$title – $following" // 'Kapitel 1' + 'Der Anfang' = eine Überschrift
                merged = i + 1
            }
            paragraph = "${"#".repeat(if (kinds[i] == "part") 1 else chapterLevel)} $title"
        }
        out.add(paragraph)
    }
    return out.joinToString("\n\n")
}

// --- PDF: Schriftgrößen auswerten --------------------------------------------------------------

/** Eine Textzeile aus dem PDF. [pageHeight] und [y] (von oben) dienen nur der Erkennung von Seitenzahlen. */
data class PdfLine(val text: String, val size: Float, val y: Float, val pageHeight: Float, val page: Int)

/** Ein Absatz des PDF mit mittlerer Schriftgröße; [atEdge] = liegt im oberen/unteren Seitenstreifen. */
data class PdfBlock(val text: String, val size: Float, val atEdge: Boolean)

private const val PAGE_MARGIN = 0.08f // oberer/unterer Seitenstreifen (Anteil der Höhe), in dem Seitenzahlen stehen
private const val SAME_BLOCK_GAP = 1.6f // maximaler Zeilenabstand (in Schriftgrößen) innerhalb eines Absatzes
private const val SAME_BLOCK_SIZE_DELTA = 0.6f

/** Fasst Zeilen (in Lesereihenfolge) zu Absätzen zusammen: gleiche Seite, ähnliche Schrift, kleiner Abstand. */
fun groupPdfLines(lines: List<PdfLine>): List<PdfBlock> {
    val blocks = mutableListOf<PdfBlock>()
    var current = mutableListOf<PdfLine>()

    fun flush() {
        if (current.isEmpty()) return
        val chars = current.sumOf { it.text.length }.coerceAtLeast(1)
        val size = current.sumOf { (it.size * it.text.length).toDouble() }.toFloat() / chars
        val first = current.first()
        val atEdge = first.y < first.pageHeight * PAGE_MARGIN || first.y > first.pageHeight * (1 - PAGE_MARGIN)
        blocks.add(PdfBlock(current.joinToString(" ") { it.text.trim() }, size, atEdge))
        current = mutableListOf()
    }

    for (line in lines) {
        if (line.text.isBlank()) continue
        val last = current.lastOrNull()
        val sameBlock = last != null && last.page == line.page &&
            kotlin.math.abs(last.size - line.size) <= SAME_BLOCK_SIZE_DELTA &&
            line.y - last.y in 0f..(line.size * SAME_BLOCK_GAP)
        if (!sameBlock) flush()
        current.add(line)
    }
    flush()
    return blocks
}

/**
 * Kurze Blöcke mit deutlich größerer Schrift als der Fließtext werden Überschriften; die größte Schrift ist
 * Ebene 1 (höchstens bis Ebene 3). Seitenzahlen am Rand fallen weg.
 */
fun markPdfHeadings(allBlocks: List<PdfBlock>): List<String> {
    if (allBlocks.isEmpty()) return emptyList()
    val charsBySize = mutableMapOf<Int, Int>()
    for (b in allBlocks) charsBySize.merge(Math.round(b.size), b.text.length, Int::plus)
    val bodySize = charsBySize.maxByOrNull { it.value }!!.key
    val blocks = allBlocks.filterNot { b ->
        b.atEdge && b.text.trim('-', '–', '—', ' ').let { it.isNotEmpty() && it.all(Char::isDigit) } && Math.round(b.size) <= bodySize
    }
    val headingSizes = blocks.filter { Math.round(it.size) >= bodySize * 1.15 && looksLikeHeading(it.text) }
        .map { Math.round(it.size) }.distinct().sortedDescending()
    return blocks.map { b ->
        val rounded = Math.round(b.size)
        if (rounded in headingSizes && looksLikeHeading(b.text)) {
            "${"#".repeat(minOf(headingSizes.indexOf(rounded) + 1, 3))} ${b.text}"
        } else b.text
    }
}

/** Absätze verbinden; ein Absatz, der über einen Seitenumbruch läuft, wird wieder zusammengesetzt. */
fun joinParagraphs(paragraphs: List<String>): String {
    val result = mutableListOf<String>()
    for (raw in paragraphs) {
        val paragraph = raw.trim()
        if (paragraph.isEmpty()) continue
        val last = result.lastOrNull()
        if (last != null && headingLevel(last) == 0 && !endsTerminal(last) && paragraph[0].isLowerCase()) {
            result[result.lastIndex] = "$last $paragraph"
        } else {
            result.add(paragraph)
        }
    }
    return result.joinToString("\n\n")
}
