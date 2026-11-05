package app.aitranslator.core

/**
 * Der Text wird einmal in Blöcke (Überschrift/Absatz) zerlegt, daraus entstehen alle Exportformate:
 * Überschriften werden echte Überschriften, Absätze bleiben Absätze, **fett** und *kursiv* bleiben erhalten
 * (TXT: ohne Auszeichnung).
 */
private val HEADING_LINE = Regex("""^(#{1,6})[ \t]+(.*\S)[ \t]*$""")
private val INLINE = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*|\*(?=\S)(.+?)(?<=\S)\*""")
private val BLANK_LINE = Regex("""\n[ \t]*\n""")

enum class BlockKind { HEADING, PARAGRAPH, IMAGE }

/** [level] nur für Überschriften (1-6), [imageId] nur für Bilder (Nummer aus dem Platzhalter `[[Bild n]]`). */
data class Block(val kind: BlockKind, val text: String, val level: Int = 0, val imageId: Int = 0)

data class Run(val text: String, val bold: Boolean, val italic: Boolean)

data class Chapter(val title: String, val blocks: List<Block>)

/**
 * Absätze (durch Leerzeilen getrennt) und '#'-Überschriften. Eine Überschrift direkt vor dem Fließtext
 * ohne Leerzeile wird abgetrennt; Zeilenumbrüche innerhalb eines Absatzes bleiben erhalten.
 */
fun parseBlocks(content: String): List<Block> {
    val blocks = mutableListOf<Block>()
    for (chunk in content.replace("\r\n", "\n").split(BLANK_LINE)) {
        val lines = mutableListOf<String>()
        for (line in chunk.split("\n")) {
            val heading = HEADING_LINE.matchEntire(line.trim())
            val imageId = imageIdOfLine(line)
            if (imageId != null) {
                if (lines.isNotEmpty()) {
                    blocks.add(Block(BlockKind.PARAGRAPH, lines.joinToString("\n")))
                    lines.clear()
                }
                blocks.add(Block(BlockKind.IMAGE, "", imageId = imageId))
            } else if (heading != null) {
                if (lines.isNotEmpty()) {
                    blocks.add(Block(BlockKind.PARAGRAPH, lines.joinToString("\n")))
                    lines.clear()
                }
                blocks.add(Block(BlockKind.HEADING, heading.groupValues[2].trim(), heading.groupValues[1].length))
            } else if (line.isNotBlank()) {
                lines.add(line.trim())
            }
        }
        if (lines.isNotEmpty()) blocks.add(Block(BlockKind.PARAGRAPH, lines.joinToString("\n")))
    }
    return blocks
}

/** Zerlegt Text mit Sternchen-Auszeichnung (fett, kursiv) in (Text, fett, kursiv)-Stücke. */
fun inlineRuns(text: String): List<Run> {
    val runs = mutableListOf<Run>()
    var pos = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > pos) runs.add(Run(text.substring(pos, match.range.first), bold = false, italic = false))
        val boldText = match.groups[1]?.value
        runs.add(if (boldText != null) Run(boldText, bold = true, italic = false) else Run(match.groups[2]!!.value, bold = false, italic = true))
        pos = match.range.last + 1
    }
    if (pos < text.length) runs.add(Run(text.substring(pos), bold = false, italic = false))
    return runs
}

fun stripInline(text: String): String = inlineRuns(text).joinToString("") { it.text }

/**
 * Überschriftenebene, die Kapitel trennt: die höchste Ebene, die mindestens zweimal vorkommt (eine einzelne
 * Titelzeile ist kein Kapitel), sonst die höchste vorhandene. 0 = keine Überschriften.
 */
fun chapterLevel(blocks: List<Block>): Int {
    val counts = blocks.filter { it.kind == BlockKind.HEADING }.groupingBy { it.level }.eachCount()
    if (counts.isEmpty()) return 0
    return counts.filterValues { it >= 2 }.keys.minOrNull() ?: counts.keys.min()
}

/** Text vor der ersten Kapitelüberschrift bildet ein Kapitel mit dem Dokumenttitel. */
fun splitChapters(blocks: List<Block>, title: String): List<Chapter> {
    val level = chapterLevel(blocks)
    if (level == 0) return listOf(Chapter(title, blocks))
    val titles = mutableListOf<String>()
    val groups = mutableListOf<MutableList<Block>>()
    for (block in blocks) {
        if (block.kind == BlockKind.HEADING && block.level <= level) {
            titles.add(stripInline(block.text))
            groups.add(mutableListOf())
        } else if (groups.isEmpty()) {
            titles.add(title)
            groups.add(mutableListOf())
        }
        groups.last().add(block)
    }
    return titles.zip(groups).map { (t, g) -> Chapter(t, g) }
}

// TXT und MD sind einzelne Textdateien: Bilder haben dort keinen Platz und entfallen.
fun toMarkdown(blocks: List<Block>): String = blocks.filter { it.kind != BlockKind.IMAGE }
    .joinToString("\n\n") { if (it.kind == BlockKind.HEADING) "${"#".repeat(it.level)} ${it.text}" else it.text } + "\n"

fun toPlainText(blocks: List<Block>): String =
    blocks.filter { it.kind != BlockKind.IMAGE }.joinToString("\n\n") { stripInline(it.text) } + "\n"
