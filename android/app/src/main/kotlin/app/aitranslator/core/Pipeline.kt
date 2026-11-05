package app.aitranslator.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Übersetzungs-Pipeline ohne UI-Abhängigkeit: Glossar erstellen, Abschnitte übersetzen, Qualität prüfen. */
const val MAX_OUTPUT_TOKENS = 4096
private const val MAX_SPLIT_DEPTH = 2
private const val MIN_SPLITTABLE_CHARS = 400
private const val RETRY_TEMPERATURE = 0.3

/** Umfang des Vorgängertextes, der als Kontext mitgeschickt wird. */
const val CONTEXT_CHARS = 1200
private const val TERMINAL_PUNCTUATION = ".!?;:…»«\"”’)。！？」』"

/** Ergebnis eines Abschnitts. [warnings] sind für den Benutzer bestimmt (Hinweisliste). */
data class ChunkResult(
    val index: Int,
    val source: String,
    val translation: String,
    val warnings: List<String> = emptyList(),
)

/**
 * Die letzten Absätze von Original und Übersetzung als Paar. Nur wenn beide gleich viele Absätze haben,
 * sonst ließe sich nicht sagen, welcher Satz zu welchem gehört - dann lieber kein Kontext.
 */
fun buildContext(source: String, translation: String, budget: Int = CONTEXT_CHARS): Context? {
    val src = splitParagraphs(source)
    val out = splitParagraphs(translation)
    if (src.isEmpty() || src.size != out.size) return null
    var taken = 0
    var size = 0
    for (paragraph in src.asReversed()) {
        if (taken > 0 && size + paragraph.length > budget) break
        taken++
        size += paragraph.length
    }
    return src.takeLast(taken).joinToString("\n\n") to out.takeLast(taken).joinToString("\n\n")
}

/**
 * Manche Modelle übersetzen den Kontext erneut mit; dann steht er vorn in der Ausgabe.
 * Bleibt nach dem Abschneiden nichts übrig, war es keine Wiederholung und die Ausgabe bleibt.
 */
internal fun stripContextEcho(text: String, contextTranslation: String): String {
    val echo = contextTranslation.trim()
    if (echo.isNotEmpty() && text.startsWith(echo)) {
        return text.substring(echo.length).trimStart().ifEmpty { text }
    }
    return text
}

/** Prüft, ob der Review-Dialog das Glossar noch ändern soll (nur bei Chat-Modellen mit KI-Vorschlag). */
typealias GlossaryReview = suspend (Map<String, String>) -> Map<String, String>

class TranslationPipeline(private val backend: LlmBackend, private val config: Config) {

    /**
     * Bei Chat-Modellen: gespeichertes Glossar plus KI-Vorschlag aus dem ersten Abschnitt, den [review]
     * korrigieren darf. TranslateGemma ist kein Chat-Modell und schlägt nichts vor - dort pflegt der Benutzer
     * das Glossar selbst, es wird unverändert zurückgegeben.
     */
    suspend fun buildGlossary(chunks: List<Chunk>, stored: Map<String, String>, review: GlossaryReview? = null): Map<String, String> {
        if (config.isTranslateGemma || chunks.isEmpty()) return stored
        val proposal = try {
            val reply = backend.chat(
                config.model, listOf(ChatMessage("user", glossaryRequest(chunks[0].text, config.langTarget))),
                maxTokens = 1024,
            )
            parseGlossary(reply.text)
        } catch (e: LlmException) {
            emptyMap()
        }
        val merged = mergeGlossary(stored, proposal)
        return review?.invoke(merged) ?: merged
    }

    /**
     * Mit [onDelta] wird gestreamt: jedes neue Textstück des ersten Übersetzungsversuchs geht sofort an
     * onDelta(chunk, text). Das endgültige Ergebnis kann davon abweichen (Wiederholung, Teilung,
     * Überschriften-Korrektur) und ersetzt den Streamtext. Abbrechen (Coroutine) wirkt auch mitten im Abschnitt.
     */
    fun translate(
        chunks: List<Chunk>, glossary: Map<String, String> = emptyMap(),
        onDelta: ((Chunk, String) -> Unit)? = null,
    ): Flow<ChunkResult> = flow {
        var previous: ChunkResult? = null
        for (chunk in chunks) {
            val context = if (previous != null && config.useContext) buildContext(previous.source, previous.translation) else null
            val stream = onDelta?.let { callback -> { delta: String -> callback(chunk, delta) } }
            val result = translateChunk(chunk, glossary, context, stream)
            previous = result
            emit(result)
        }
    }

    suspend fun translateChunk(
        chunk: Chunk, glossary: Map<String, String>, context: Context? = null,
        onDelta: ((String) -> Unit)? = null,
    ): ChunkResult {
        val (text, warnings) = translateText(chunk.text, glossary, context, depth = 0, onDelta = onDelta)
        return ChunkResult(chunk.index, chunk.text, restoreHeadings(chunk.text, text), warnings)
    }

    private suspend fun translateText(
        text: String, glossary: Map<String, String>, context: Context?, depth: Int,
        onDelta: ((String) -> Unit)? = null,
    ): Pair<String, List<String>> {
        val terms = relevantTerms(glossary, text)
        val completion = generate(text, terms, context, temperature = 0.0, onDelta = onDelta)

        // Abgeschnitten: eine Wiederholung mit identischen Parametern lieferte dasselbe Ergebnis,
        // deshalb den Text halbieren und die Hälften einzeln übersetzen.
        if (completion.finishReason == "length" && depth < MAX_SPLIT_DEPTH && text.length > MIN_SPLITTABLE_CHARS) {
            val parts = splitIntoChunks(text, text.length / 2 + 100)
            if (parts.size > 1) {
                val outputs = mutableListOf<String>()
                val warnings = mutableListOf("Abschnitt wegen Token-Limit geteilt und neu übersetzt")
                var partContext = context
                for (part in parts) {
                    val (out, partWarnings) = translateText(part.text, glossary, partContext, depth + 1)
                    outputs.add(out)
                    partContext = if (config.useContext) buildContext(part.text, out) else null
                    warnings.addAll(partWarnings.filter { it !in warnings })
                }
                return outputs.joinToString("\n\n") to warnings
            }
        }

        val warnings = check(text, completion, terms)
        if (warnings.isNotEmpty() && completion.finishReason != "length") {
            val retry = generate(text, terms, context, temperature = RETRY_TEMPERATURE)
            val retryWarnings = check(text, retry, terms)
            if (retryWarnings.size < warnings.size) return retry.text to retryWarnings
        }
        return completion.text to warnings
    }

    private suspend fun generate(
        text: String, terms: Map<String, String>, context: Context?, temperature: Double,
        onDelta: ((String) -> Unit)? = null,
    ): Completion {
        val cfg = config
        var completion = if (cfg.isTranslateGemma) {
            backend.complete(
                cfg.model, gemmaPrompt(text, cfg.langSource, cfg.langTarget, terms, context),
                maxTokens = MAX_OUTPUT_TOKENS, temperature = temperature, stop = GEMMA_STOP, onDelta = onDelta,
            )
        } else {
            val system = chatSystemPrompt(cfg.translationPrompt, cfg.langSource, cfg.langTarget, terms)
            backend.chat(
                cfg.model, chatMessages(system, text, context),
                maxTokens = MAX_OUTPUT_TOKENS, temperature = temperature, onDelta = onDelta,
            )
        }
        if (context != null) completion = completion.copy(text = stripContextEcho(completion.text, context.second))
        return completion
    }

    private fun check(source: String, completion: Completion, terms: Map<String, String>): List<String> {
        val warnings = mutableListOf<String>()
        if (completion.finishReason == "length") warnings.add("Ausgabe abgeschnitten (Token-Limit)")
        if (completion.text.isEmpty()) {
            warnings.add("Leere Antwort")
        } else if (endsTerminal(source) && !endsTerminal(completion.text)) {
            warnings.add("Übersetzung endet ohne Satzzeichen - evtl. unvollständig")
        }
        if (completion.text.isNotEmpty()) {
            warnings += missingTerms(terms, completion.text).map { "Glossar nicht eingehalten: $it → ${terms.getValue(it)}" }
        }
        return warnings
    }

    private fun endsTerminal(text: String): Boolean = text.trimEnd().lastOrNull()?.let { it in TERMINAL_PUNCTUATION } ?: false
}
