package app.aitranslator.core

/**
 * Fehler des Backends; nie als Text zurückgeben, sonst landet eine Fehlermeldung als 'Übersetzung' im Dokument.
 * [transient] = voraussichtlich vorübergehend (Verbindung abgerissen, Zeitüberschreitung, Serverfehler 5xx):
 * ein erneuter Versuch lohnt sich. Falsche Zugangsdaten oder eine ungültige Anfrage (4xx) bleiben dauerhaft.
 */
class LlmException(message: String, cause: Throwable? = null, val transient: Boolean = false) : Exception(message, cause)

data class Completion(
    val text: String,
    /** "stop" = fertig, "length" = Token-Limit erreicht (Ausgabe abgeschnitten). */
    val finishReason: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/**
 * Schnittstelle zum Sprachmodell. Heute gibt es [LmStudioBackend] (HTTP, OpenAI-kompatibel); ein Modell auf
 * dem Gerät (z. B. llama.cpp) ließe sich als weitere Implementierung anschließen, ohne die Pipeline zu ändern.
 *
 * Mit [onDelta] wird gestreamt: jedes neue Textstück geht sofort an onDelta; das Ergebnis ist trotzdem das
 * vollständige [Completion]. Abbrechen geschieht über die Coroutine.
 */
interface LlmBackend {
    suspend fun listModels(): List<String>

    suspend fun complete(
        model: String, prompt: String, maxTokens: Int, temperature: Double = 0.0,
        stop: List<String>? = null, onDelta: ((String) -> Unit)? = null,
    ): Completion

    suspend fun chat(
        model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double = 0.0,
        onDelta: ((String) -> Unit)? = null,
    ): Completion
}
