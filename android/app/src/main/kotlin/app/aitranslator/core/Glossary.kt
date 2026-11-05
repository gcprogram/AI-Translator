package app.aitranslator.core

/** Glossar: 'Begriff: Übersetzung'-Zeilen, Groß-/Kleinschreibung bleibt erhalten. */
private val BULLET = Regex("""^$WS*(?:[-*•]+|\d+[.)])$WS+""")

/** Liest Zeilen 'Begriff: Übersetzung' - auch KI-Antworten mit Aufzählungszeichen oder **fett**. */
fun parseGlossary(text: String): Map<String, String> {
    val glossary = linkedMapOf<String, String>()
    for (raw in text.lines()) {
        val line = BULLET.replace(raw, "").replace("**", "").replace("`", "")
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val term = line.substring(0, colon).trim()
        val translation = line.substring(colon + 1).trim()
        if (term.isNotEmpty() && translation.isNotEmpty()) glossary[term] = translation
    }
    return glossary
}

/** Bestehende Einträge gewinnen; nur wirklich neue Begriffe (case-insensitiv) kommen hinzu. */
fun mergeGlossary(existing: Map<String, String>, proposal: Map<String, String>): Map<String, String> {
    val merged = LinkedHashMap(existing)
    val known = merged.keys.map { it.lowercase() }.toMutableSet()
    for ((term, translation) in proposal) {
        if (term.lowercase() !in known) {
            merged[term] = translation
            known.add(term.lowercase())
        }
    }
    return merged
}

/** Wortanfang + Begriff, ohne Wortende: findet auch Beugung und Plural ('Potion' in 'Potions'). */
private fun wordStart(term: String) = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(term), RegexOption.IGNORE_CASE)

/** Nur die Begriffe, die im Text vorkommen - hält den Prompt klein. */
fun relevantTerms(glossary: Map<String, String>, text: String): Map<String, String> =
    glossary.filterKeys { wordStart(it).containsMatchIn(text) }

/**
 * Begriffe, deren vorgegebene Übersetzung im Ergebnis fehlt. Die letzten zwei Buchstaben werden ignoriert,
 * damit deutsche Beugung ('Vielsafttranks') keinen Fehlalarm auslöst.
 */
fun missingTerms(terms: Map<String, String>, translation: String): List<String> =
    terms.filter { (_, tr) ->
        !wordStart(if (tr.length >= 6) tr.dropLast(2) else tr).containsMatchIn(translation)
    }.keys.toList()

fun formatGlossary(glossary: Map<String, String>): String =
    glossary.keys.sortedBy { it.lowercase() }.joinToString("") { "$it: ${glossary.getValue(it)}\n" }
