package app.aitranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LmStudioBackendTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: LmStudioBackend

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        backend = LmStudioBackend(server.url("/").toString().trimEnd('/'), apiKey = "geheim")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun sse(vararg events: String, done: Boolean = true) = MockResponse()
        .setHeader("Content-Type", "text/event-stream") // ohne charset: nur UTF-8-Dekodierung per Hand ist korrekt
        .setBody(events.joinToString("") { "data: $it\n\n" } + if (done) "data: [DONE]\n\n" else "")

    @Test
    fun completeReturnsTrimmedTextAndFinishReasonAndSendsToken() = runBlocking {
        server.enqueue(json("""{"choices":[{"text":" Hallo ","finish_reason":"length"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}"""))
        val c = backend.complete("m", "p", maxTokens = 5, stop = listOf("<end_of_turn>"))
        assertEquals(Completion("Hallo", "length", 3, 4), c)
        val request = server.takeRequest()
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals("/v1/completions", request.path)
        assertTrue("\"stop\":[\"<end_of_turn>\"]" in request.body.readUtf8())
    }

    @Test
    fun chatReadsMessageContent() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}"""))
        assertEquals("Hi", backend.chat("m", listOf(ChatMessage("user", "x")), maxTokens = 5).text)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun completionStreamDeliversDeltasAndDecodesUtf8() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"text":"Hal","finish_reason":null}]}""",
                """{"choices":[{"text":"lo ä","finish_reason":null}]}""",
                """{"choices":[{"text":"— Welt","finish_reason":null}]}""",
                """{"choices":[{"text":"","finish_reason":"stop"}]}""",
            ),
        )
        val deltas = mutableListOf<String>()
        val c = backend.complete("m", "p", maxTokens = 5) { deltas += it }
        assertEquals(listOf("Hal", "lo ä", "— Welt"), deltas)
        assertEquals(Completion("Hallo ä— Welt", "stop"), c)
        assertTrue("\"stream\":true" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun chatStreamReadsDeltaContent() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":"Hallo "},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":"Welt"},"finish_reason":"stop"}]}""",
            ),
        )
        val deltas = mutableListOf<String>()
        val c = backend.chat("m", listOf(ChatMessage("user", "x")), maxTokens = 5) { deltas += it }
        assertEquals("Hallo Welt", deltas.joinToString(""))
        assertEquals("stop", c.finishReason)
    }

    @Test
    fun streamWithoutFinishReasonCountsAsTruncated() = runBlocking {
        server.enqueue(sse("""{"choices":[{"text":"abc","finish_reason":null}]}"""))
        assertEquals("length", backend.complete("m", "p", maxTokens = 5) { }.finishReason)
    }

    @Test
    fun exceptionInCallbackStopsTheStream() {
        class Stop : RuntimeException()
        server.enqueue(sse("""{"choices":[{"text":"a"}]}""", """{"choices":[{"text":"b"}]}"""))
        assertThrows(Stop::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) { throw Stop() } } }
    }

    @Test
    fun serverErrorsRaiseInsteadOfReturningText() {
        server.enqueue(json("<html>kaputt</html>", 500))
        assertTrue(assertThrows(LlmException::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) } }.message!!.contains("500"))
        server.enqueue(json("""{"nichts":1}"""))
        assertThrows(LlmException::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) } }
        server.enqueue(json("kein json"))
        assertThrows(LlmException::class.java) { runBlocking { backend.chat("m", emptyList(), maxTokens = 5) } }
        server.enqueue(json("""{"choices":[{"text":"a","finish_reason":null}]}""", 200).setBody("data: {kaputt}\n\n"))
        assertThrows(LlmException::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) { } } }
    }

    @Test
    fun unauthorizedGivesHint() {
        server.enqueue(json("{}", 401)) // native Modellliste
        server.enqueue(json("{}", 401)) // Fallback auf /v1/models
        assertTrue(assertThrows(LlmException::class.java) { runBlocking { backend.listModels() } }.message!!.contains("API-Schlüssel"))
    }

    @Test
    fun errorEventInsideStreamRaises() {
        server.enqueue(sse("""{"error":{"message":"model unloaded"}}"""))
        assertTrue(assertThrows(LlmException::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) { } } }.message!!.contains("model unloaded"))
    }

    @Test
    fun unreachableServerRaises() {
        val dead = LmStudioBackend("http://127.0.0.1:1")
        assertThrows(LlmException::class.java) { runBlocking { dead.listModels() } }
    }

    @Test
    fun invalidAddressRaises() {
        assertThrows(LlmException::class.java) { runBlocking { LmStudioBackend("http://").listModels() } }
    }

    @Test
    fun listModelsSkipsEmbeddingsAndFallsBackToOpenaiList() = runBlocking {
        server.enqueue(json("""{"models":[{"key":"a","type":"llm"},{"key":"emb","type":"embedding"}]}"""))
        assertEquals(listOf("a"), backend.listModels())
        server.enqueue(json("{}", 404))
        server.enqueue(json("""{"data":[{"id":"x"},{"id":"y"}]}"""))
        assertEquals(listOf("x", "y"), backend.listModels())
    }

    @Test
    fun cancellingAbortsAHangingRequestPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) // Server antwortet nie
        val call = async(Dispatchers.IO) { backend.complete("m", "p", maxTokens = 5) }
        delay(300)
        call.cancel()
        withTimeout(5_000) { runCatching { call.await() } } // würde ohne Abbruch 300 s hängen
        assertTrue(call.isCancelled)
        assertFalse(call.isActive)
    }

    @Test
    fun cancellingInTheMiddleOfAStreamStopsReading() = runBlocking {
        server.enqueue(sse("""{"choices":[{"text":"a"}]}""", """{"choices":[{"text":"b"}]}""", """{"choices":[{"text":"c"}]}"""))
        val seen = mutableListOf<String>()
        lateinit var job: Deferred<Completion>
        // LAZY: erst starten, wenn job zugewiesen ist, sonst könnte der Callback vor der Zuweisung laufen
        job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            backend.complete("m", "p", maxTokens = 5) {
                seen += it
                if (seen.size == 1) job.cancel()
            }
        }
        job.start()
        val outcome = withTimeout(5_000) { runCatching { job.await() }.exceptionOrNull() }
        assertTrue(outcome is CancellationException)
        assertEquals(listOf("a"), seen)
    }
}
