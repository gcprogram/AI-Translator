package app.aitranslator.core

import kotlinx.serialization.Serializable

const val MIN_CHUNK_CHARS = 500
const val MAX_CHUNK_CHARS = 20000
const val DEFAULT_CHUNK_CHARS = 3500

/** {source}/{target} werden beim Senden ersetzt. Wird nur für Nicht-TranslateGemma-Modelle benutzt. */
const val DEFAULT_PROMPT = "Translate the following text from {source} to {target}. " +
    "Output only the translation, without comments. Preserve Markdown formatting."

/** Einstellungen. Unvollständige oder kaputte Werte führen nie zum Absturz, sondern zu Defaults ([sanitized]). */
@Serializable
data class Config(
    val ip: String = "192.168.0.10",
    val port: String = "1234",
    val apiKey: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val langSource: String = "en",
    val langTarget: String = "de",
    val translationPrompt: String = DEFAULT_PROMPT,
    val maxChunkChars: Int = DEFAULT_CHUNK_CHARS,
    val stream: Boolean = true,
    val useContext: Boolean = true,
    val glossaryName: String = DEFAULT_GLOSSARY,
) {
    val baseUrl: String get() = "http://$ip:$port"
    val isTranslateGemma: Boolean get() = "translategemma" in model.lowercase()

    fun sanitized(): Config = copy(
        ip = ip.trim().ifEmpty { "192.168.0.10" },
        port = port.trim().ifEmpty { "1234" },
        langSource = Languages.normalizeCode(langSource).ifEmpty { "en" },
        langTarget = Languages.normalizeCode(langTarget).ifEmpty { "de" },
        maxChunkChars = maxChunkChars.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS),
        translationPrompt = translationPrompt.ifBlank { DEFAULT_PROMPT },
        glossaryName = glossaryName.trim().ifEmpty { DEFAULT_GLOSSARY },
    )

    companion object {
        const val DEFAULT_GLOSSARY = "Standard"
    }
}
