package app.aitranslator.core

import kotlinx.serialization.Serializable

/** Wo übersetzt wird: LM Studio im Netzwerk oder ein Modell auf dem Gerät (llama.cpp). */
const val BACKEND_LMSTUDIO = "lmstudio"
const val BACKEND_DEVICE = "device"

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
    val backend: String = BACKEND_LMSTUDIO,
    /** Gewählte GGUF-Datei für das Backend auf dem Gerät (Adresse des Dateiwählers) und ihr Anzeigename. */
    val deviceModelUri: String = "",
    val deviceModelName: String = "",
) {
    val baseUrl: String get() = "http://$ip:$port"
    val isOnDevice: Boolean get() = backend == BACKEND_DEVICE

    /** Modellname des aktiven Backends; daran erkennt die Pipeline TranslateGemma. */
    val activeModel: String get() = if (isOnDevice) deviceModelName else model

    /** Ist alles gewählt, was zum Übersetzen nötig ist (Modell bzw. Modelldatei)? */
    val isConfigured: Boolean get() = if (isOnDevice) deviceModelUri.isNotBlank() else model.isNotBlank()
    val isTranslateGemma: Boolean get() = "translategemma" in activeModel.lowercase()

    fun sanitized(): Config = copy(
        ip = ip.trim().ifEmpty { "192.168.0.10" },
        port = port.trim().ifEmpty { "1234" },
        langSource = Languages.normalizeCode(langSource).ifEmpty { "en" },
        langTarget = Languages.normalizeCode(langTarget).ifEmpty { "de" },
        maxChunkChars = maxChunkChars.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS),
        translationPrompt = translationPrompt.ifBlank { DEFAULT_PROMPT },
        glossaryName = glossaryName.trim().ifEmpty { DEFAULT_GLOSSARY },
        backend = if (backend == BACKEND_DEVICE) BACKEND_DEVICE else BACKEND_LMSTUDIO,
    )

    companion object {
        const val DEFAULT_GLOSSARY = "Standard"
    }
}
