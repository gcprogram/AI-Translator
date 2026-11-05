package app.aitranslator.core

/** Prompt-Aufbau. Der TranslateGemma-Prompt bildet das Chat-Template des Modells nach. */
val GEMMA_STOP = listOf("<end_of_turn>")

/** (Original, Übersetzung) des Textes direkt vor dem aktuellen Abschnitt. */
typealias Context = Pair<String, String>

data class ChatMessage(val role: String, val content: String)

private fun glossaryLines(glossary: Map<String, String>): String =
    glossary.toSortedMap().entries.joinToString("; ") { "${it.key} = ${it.value}" }

private fun gemmaUserTurn(text: String, sourceCode: String, targetCode: String, glossary: Map<String, String>): String {
    val src = Languages.name(sourceCode)
    val tgt = Languages.name(targetCode)
    val srcCode = Languages.normalizeCode(sourceCode)
    val tgtCode = Languages.normalizeCode(targetCode)
    val terms = if (glossary.isNotEmpty()) "Always translate these terms exactly as given: ${glossaryLines(glossary)}. " else ""
    return "<start_of_turn>user\n" +
        "You are a professional $src ($srcCode) to $tgt ($tgtCode) translator. " +
        "Your goal is to accurately convey the meaning and nuances of the original $src text " +
        "while adhering to $tgt grammar, vocabulary, and cultural sensitivities.\n" +
        "Produce only the $tgt translation, without any additional explanations or commentary. " +
        "Preserve the Markdown format in the translation. $terms" +
        "Please translate the following Markdown text from $src into $tgt:\n\n\n" +
        "${text.trim()}<end_of_turn>\n"
}

/**
 * Roh-Prompt für /v1/completions (Template: jinja_template_aus_LM-Studio.txt, Variante 'text').
 *
 * Ergänzungen gegenüber dem Original-Template: Markdown soll erhalten bleiben; passende Glossarbegriffe
 * stehen als Satz im Anweisungstext; der Vorgängerabschnitt steht als bereits beantworteter Gesprächszug
 * davor, damit Namen, Anrede und Ton über die Abschnittsgrenze hinweg gleich bleiben.
 */
fun gemmaPrompt(
    text: String, sourceCode: String, targetCode: String,
    glossary: Map<String, String> = emptyMap(), context: Context? = null,
): String {
    val history = if (context != null) {
        gemmaUserTurn(context.first, sourceCode, targetCode, glossary) +
            "<start_of_turn>model\n${context.second.trim()}<end_of_turn>\n"
    } else ""
    return history + gemmaUserTurn(text, sourceCode, targetCode, glossary) + "<start_of_turn>model\n"
}

fun chatSystemPrompt(template: String, sourceCode: String, targetCode: String, glossary: Map<String, String>): String {
    var prompt = template.replace("{source}", Languages.name(sourceCode)).replace("{target}", Languages.name(targetCode))
    if (glossary.isNotEmpty()) {
        val terms = glossary.toSortedMap().entries.joinToString("\n") { "- ${it.key} = ${it.value}" }
        prompt += "\n\nUse this glossary consistently:\n$terms"
    }
    return prompt
}

fun chatMessages(system: String, text: String, context: Context? = null): List<ChatMessage> {
    val messages = mutableListOf(ChatMessage("system", system))
    if (context != null) {
        messages += ChatMessage("user", context.first)
        messages += ChatMessage("assistant", context.second)
    }
    messages += ChatMessage("user", text)
    return messages
}

fun glossaryRequest(sample: String, targetCode: String): String =
    "Create a glossary for translation into ${Languages.name(targetCode)}. " +
        "Extract only special and technical terms, proper names, and fictional terms. " +
        "List only single words or connected word groups. " +
        "Answer with one entry per line in the format 'Original: Translation'. " +
        "If no term is found, answer with an empty message.\n\n" +
        "Text:\n$sample"
