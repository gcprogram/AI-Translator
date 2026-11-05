package app.aitranslator.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** HTTP-Client für LM Studio bzw. jeden OpenAI-kompatiblen Server. */
class LmStudioBackend(
    baseUrl: String,
    private val apiKey: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build(),
) : LlmBackend {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String): Request.Builder {
        val builder = try {
            Request.Builder().url("$base$path")
        } catch (e: IllegalArgumentException) {
            throw LlmException("Ungültige Serveradresse: $base", e)
        }
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        return builder
    }

    private fun post(path: String, payload: JsonObject): Request =
        request(path).post(payload.toString().toRequestBody("application/json".toMediaType())).build()

    /**
     * Führt den Aufruf blockierend auf dem IO-Dispatcher aus. Wird die Coroutine abgebrochen, bricht ein
     * Wächter den HTTP-Aufruf ab - auch mitten im Warten auf das nächste Textstück.
     */
    private suspend fun <T> execute(request: Request, readTimeoutSeconds: Long, handle: CoroutineScope.(Response) -> T): T =
        withContext(Dispatchers.IO) {
            val call = client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build().newCall(request)
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    val response = try {
                        call.execute()
                    } catch (e: SocketTimeoutException) {
                        ensureActive()
                        throw LlmException("Zeitüberschreitung bei ${request.url}", e, transient = true)
                    } catch (e: IOException) {
                        ensureActive()
                        throw LlmException("Server nicht erreichbar (${request.url}): ${e.message}", e, transient = true)
                    }
                    response.use {
                        if (it.code == 401) throw LlmException("Nicht autorisiert - API-Schlüssel prüfen.")
                        if (it.code != 200) {
                            throw LlmException("Serverfehler ${it.code}: ${it.body?.string().orEmpty().take(300)}", transient = it.code >= 500)
                        }
                        handle(it)
                    }
                } finally {
                    watcher.cancel()
                }
            }
        }

    private suspend fun getJson(path: String, timeoutSeconds: Long): JsonObject =
        execute(request(path).get().build(), timeoutSeconds) { response ->
            val body = response.body?.string().orEmpty()
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw LlmException("Antwort ist kein gültiges JSON: ${body.take(200)}", e)
            }
        }

    /** Bevorzugt LM Studios native API (/api/v1/models, ohne Embedding-Modelle), sonst /v1/models. */
    override suspend fun listModels(): List<String> {
        try {
            val models = getJson("/api/v1/models", 10)["models"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["key"] != null && it["type"]?.jsonPrimitive?.contentOrNull != "embedding" }
                .map { it.getValue("key").jsonPrimitive.content }
            if (models.isNotEmpty()) return models
        } catch (e: LlmException) {
            // Fallback auf die OpenAI-kompatible Liste
        }
        return getJson("/v1/models", 10)["data"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }

    override suspend fun complete(
        model: String, prompt: String, maxTokens: Int, temperature: Double, stop: List<String>?,
        onDelta: ((String) -> Unit)?,
    ): Completion {
        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", onDelta != null)
            if (!stop.isNullOrEmpty()) put("stop", buildJsonArray { stop.forEach { add(JsonPrimitive(it)) } })
        }
        val extractText = { choice: JsonObject -> choice["text"]?.jsonPrimitive?.contentOrNull }
        return if (onDelta != null) stream("/v1/completions", payload, extractText, onDelta)
        else completion("/v1/completions", payload, extractText)
    }

    override suspend fun chat(
        model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double,
        onDelta: ((String) -> Unit)?,
    ): Completion {
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { add(buildJsonObject { put("role", it.role); put("content", it.content) }) }
            })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", onDelta != null)
        }
        return if (onDelta != null) {
            stream("/v1/chat/completions", payload, { it["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull }, onDelta)
        } else {
            completion("/v1/chat/completions", payload) { it["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull }
        }
    }

    private suspend fun completion(path: String, payload: JsonObject, extract: (JsonObject) -> String?): Completion =
        execute(post(path, payload), READ_TIMEOUT_SECONDS) { response ->
            val body = response.body?.string().orEmpty()
            val data = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw LlmException("Antwort ist kein gültiges JSON: ${body.take(200)}", e)
            }
            try {
                val choice = data.getValue("choices").jsonArray[0].jsonObject
                val usage = data["usage"] as? JsonObject
                Completion(
                    text = (extract(choice) ?: throw NoSuchElementException("Text fehlt")).trim(),
                    finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "stop",
                    promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                    completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                )
            } catch (e: Exception) {
                if (e is LlmException) throw e
                throw LlmException("Unerwartetes Antwortformat: ${data.toString().take(300)}", e)
            }
        }

    /** Ein Ereignis des Streams: Textstück und (falls vorhanden) Abschlussgrund. */
    private fun parseEvent(body: String, extract: (JsonObject) -> String?): Pair<String, String?> {
        try {
            val data = json.parseToJsonElement(body).jsonObject
            data["error"]?.let { throw LlmException("Serverfehler im Stream: ${it.toString().take(300)}") }
            val choice = data.getValue("choices").jsonArray[0].jsonObject
            return extract(choice).orEmpty() to choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Unerwartetes Stream-Format: ${e.message}", e)
        }
    }

    /**
     * Server-Sent Events lesen und jedes Textstück an [onDelta] geben. Ausnahmen aus [onDelta] (etwa der Abbruch)
     * laufen unverändert nach außen und beenden den Stream.
     */
    private suspend fun stream(
        path: String, payload: JsonObject, extract: (JsonObject) -> String?, onDelta: (String) -> Unit,
    ): Completion = execute(post(path, payload), READ_TIMEOUT_SECONDS) { response ->
        val source = response.body?.source() ?: throw LlmException("Leere Antwort des Servers")
        val parts = StringBuilder()
        var finishReason = ""
        while (true) {
            ensureActive()
            val line = try {
                source.readUtf8Line()?.trim()
            } catch (e: IOException) {
                ensureActive()
                throw LlmException("Verbindung während der Übersetzung abgebrochen: ${e.message}", e, transient = true)
            } ?: break
            if (!line.startsWith("data:")) continue
            val body = line.removePrefix("data:").trim()
            if (body == "[DONE]") break
            val (delta, finish) = parseEvent(body, extract)
            if (delta.isNotEmpty()) {
                parts.append(delta)
                onDelta(delta)
            }
            if (finish != null) finishReason = finish
        }
        // Stream ohne Abschluss: lieber als abgeschnitten behandeln als als fertig
        Completion(parts.toString().trim(), finishReason.ifEmpty { "length" })
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 300L
    }
}
