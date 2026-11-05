package app.aitranslator.core

/** Zerlegt Text in Abschnitte, ohne Sätze zu zerreißen und ohne Text zu verlieren. */

/**
 * Leerraum. Bewusst keine Flags wie (?U): Androids Regex-Engine (ICU) kennt sie nicht, und \s allein ist auf der
 * JVM nur ASCII, auf Android auch Unicode. Diese Klasse verhält sich überall gleich.
 */
internal const val WS = "[\\s\\p{Z}]"

// Satzende inkl. schließender Anführungszeichen. Westliche Zeichen verlangen folgenden Leerraum,
// CJK-Satzzeichen nicht (dort steht kein Leerzeichen dahinter).
private val SENTENCE_END = Regex("""(?:[.!?…]["'»”’)\]]*$WS+|[。！？]["」』”’)\]]*$WS*)""")
private val PARAGRAPH = Regex(""".*?(?:\n[ \t]*\n$WS*|\z)""", RegexOption.DOT_MATCHES_ALL)
private val HEADING = Regex("""^(#{1,6})[ \t]+\S""")
private val PARAGRAPH_SPLIT = Regex("""\n[ \t]*\n""")

/** Ein Kapitel beginnt einen neuen Abschnitt, sobald der aktuelle mindestens so viel vom Limit füllt. */
private const val CHAPTER_BREAK_FILL = 4

data class Chunk(val index: Int, val text: String)

/** 1-6 für Markdown-Überschriften ('## Titel'), sonst 0. */
fun headingLevel(text: String): Int = HEADING.find(text.trimStart())?.groupValues?.get(1)?.length ?: 0

fun splitParagraphs(text: String): List<String> =
    text.split(PARAGRAPH_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Kleine Modelle verlieren gern das '#' vor Überschriften. Stimmt die Absatzzahl überein, wird das Präfix
 * aus dem Original wieder eingesetzt; sonst bleibt die Übersetzung unverändert.
 */
fun restoreHeadings(source: String, translation: String): String {
    val src = splitParagraphs(source)
    val out = splitParagraphs(translation).toMutableList()
    if (src.size != out.size) return translation
    var changed = false
    for (i in src.indices) {
        val level = headingLevel(src[i])
        if (level > 0 && headingLevel(out[i]) == 0) {
            out[i] = "${"#".repeat(level)} ${out[i].trimStart('#').trim()}"
            changed = true
        }
    }
    return if (changed) out.joinToString("\n\n") else translation
}

/** Zerlegt text nach pattern; das Trennstück bleibt am vorherigen Teil (nichts geht verloren). */
private fun pieces(text: String, pattern: Regex): List<String> {
    val result = mutableListOf<String>()
    var pos = 0
    for (match in pattern.findAll(text)) {
        val end = match.range.last + 1
        if (end == pos) continue
        result.add(text.substring(pos, end))
        pos = end
    }
    if (pos < text.length) result.add(text.substring(pos))
    return result.filter { it.isNotEmpty() }
}

private fun hardSplit(text: String, limit: Int): List<String> {
    val parts = mutableListOf<String>()
    var rest = text
    while (rest.length > limit) {
        var cut = rest.lastIndexOf(' ', limit - 1)
        if (cut < limit / 2) cut = limit // kein brauchbares Leerzeichen (z. B. CJK)
        parts.add(rest.substring(0, cut))
        rest = rest.substring(cut)
    }
    parts.add(rest)
    return parts
}

/** Absätze; überlange Absätze werden an Satzenden, überlange Sätze hart geteilt. */
private fun units(text: String, limit: Int): List<String> {
    val units = mutableListOf<String>()
    for (paragraph in pieces(text, PARAGRAPH)) {
        if (paragraph.length <= limit) {
            units.add(paragraph)
            continue
        }
        for (sentence in pieces(paragraph, SENTENCE_END)) {
            if (sentence.length <= limit) units.add(sentence) else units.addAll(hardSplit(sentence, limit))
        }
    }
    return units
}

/**
 * Abschnitte an Absatzgrenzen. Eine Überschrift bleibt bei ihrem Folgeabsatz, und ein Kapitel
 * (Überschrift Ebene 1-2) beginnt nach Möglichkeit einen neuen Abschnitt.
 */
fun splitIntoChunks(text: String, maxChars: Int): List<Chunk> {
    require(maxChars >= 1) { "maxChars muss positiv sein" }
    val chunks = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var size = 0
    for (unit in units(text, maxChars)) {
        val level = headingLevel(unit)
        val startsChapter = level in 1..2 && size >= maxChars / CHAPTER_BREAK_FILL
        if (current.isNotEmpty() && (size + unit.length > maxChars || startsChapter)) {
            val carry = mutableListOf<String>()
            while (current.size > 1 && headingLevel(current.last()) > 0) { // keine Überschrift am Abschnittsende
                carry.add(0, current.removeAt(current.lastIndex))
            }
            chunks.add(current)
            current = carry
            size = carry.sumOf { it.length }
        }
        current.add(unit)
        size += unit.length
    }
    chunks.add(current)
    return chunks.map { it.joinToString("").trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { i, t -> Chunk(i + 1, t) }
}
