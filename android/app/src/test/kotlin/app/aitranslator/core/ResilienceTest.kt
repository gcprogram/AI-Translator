@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.aitranslator.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backend, das die ersten [failures] Aufrufe mit einem Fehler beantwortet und danach normal arbeitet. */
private class FlakyBackend(private val failures: Int, private val transient: Boolean = true) : LlmBackend {
    var calls = 0
    val prompts = mutableListOf<String>()

    override suspend fun listModels() = emptyList<String>()

    override suspend fun complete(
        model: String, prompt: String, maxTokens: Int, temperature: Double, stop: List<String>?, onDelta: ((String) -> Unit)?,
    ): Completion {
        calls++
        prompts += prompt
        if (calls <= failures) throw LlmException("Server nicht erreichbar", transient = transient)
        return Completion("Übersetzt.", "stop")
    }

    override suspend fun chat(
        model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double, onDelta: ((String) -> Unit)?,
    ): Completion = error("nicht benutzt")
}

private fun gemma() = Config(model = "google.translategemma-4b-it", langSource = "en", langTarget = "de").sanitized()

class ConnectionRetryTest {
    @Test
    fun transientErrorsAreRetriedWithGrowingWaitAndThenSucceed() = runTest {
        val backend = FlakyBackend(failures = 2)
        val retries = mutableListOf<Int>()
        val result = TranslationPipeline(backend, gemma(), onRetry = { attempt, _ -> retries += attempt })
            .translateChunk(Chunk(1, "One."), emptyMap())
        assertEquals("Übersetzt.", result.translation)
        assertEquals(listOf(1, 2), retries)
        assertEquals(3, backend.calls)
        assertEquals(3_000L + 10_000L, currentTime) // Wartezeiten 3 s und 10 s
    }

    @Test
    fun givesUpAfterTheLastRetryAndReportsTheOriginalError() = runTest {
        val backend = FlakyBackend(failures = 100)
        val retries = mutableListOf<Int>()
        val failure = runCatching {
            TranslationPipeline(backend, gemma(), onRetry = { attempt, _ -> retries += attempt }).translateChunk(Chunk(1, "One."), emptyMap())
        }.exceptionOrNull()
        assertTrue(failure is LlmException)
        assertEquals("Server nicht erreichbar", failure?.message)
        assertEquals(listOf(1, 2, 3), retries)
        assertEquals(4, backend.calls) // erster Versuch + drei Wiederholungen
    }

    @Test
    fun permanentErrorsAreNotRetried() = runTest {
        val backend = FlakyBackend(failures = 100, transient = false)
        var retried = false
        val failure = runCatching {
            TranslationPipeline(backend, gemma(), onRetry = { _, _ -> retried = true }).translateChunk(Chunk(1, "One."), emptyMap())
        }.exceptionOrNull()
        assertTrue(failure is LlmException)
        assertFalse(retried)
        assertEquals(1, backend.calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun retryHappensForEveryChunkIndependently() = runTest {
        val backend = FlakyBackend(failures = 1)
        val results = TranslationPipeline(backend, gemma()).translate(listOf(Chunk(1, "One."), Chunk(2, "Two."))).toList()
        assertEquals(2, results.size)
        assertEquals(3, backend.calls) // Chunk 1: Fehler + Erfolg, Chunk 2: Erfolg
    }
}

class ResumeTest {
    @Test
    fun initialContextIsSentWithTheFirstChunkWhenResuming() = runTest {
        val backend = FlakyBackend(failures = 0)
        TranslationPipeline(backend, gemma())
            .translate(listOf(Chunk(5, "Fünf.")), initialContext = "Four." to "Vier.").toList()
        assertTrue("Four.<end_of_turn>\n<start_of_turn>model\nVier.<end_of_turn>" in backend.prompts[0])
    }

    @Test
    fun initialContextIsIgnoredWhenContextIsSwitchedOff() = runTest {
        val backend = FlakyBackend(failures = 0)
        TranslationPipeline(backend, gemma().copy(useContext = false))
            .translate(listOf(Chunk(5, "Fünf.")), initialContext = "Four." to "Vier.").toList()
        assertFalse("Four." in backend.prompts[0])
    }

    @Test
    fun onlyTheFirstChunkGetsTheInitialContextThenThePreviousResultTakesOver() = runTest {
        val backend = FlakyBackend(failures = 0)
        TranslationPipeline(backend, gemma())
            .translate(listOf(Chunk(5, "Five."), Chunk(6, "Six.")), initialContext = "Four." to "Vier.").toList()
        assertTrue("Vier." in backend.prompts[0])
        assertFalse("Vier." in backend.prompts[1]) // Chunk 6 sieht Chunk 5 als Kontext, nicht mehr Chunk 4
        assertTrue("Five." in backend.prompts[1])
    }
}

class ErrorClassificationTest {
    private val server = MockWebServer().apply { start() }
    private val backend = LmStudioBackend(server.url("/").toString().trimEnd('/'))

    private fun failureFor(code: Int): LlmException {
        server.enqueue(MockResponse().setResponseCode(code).setBody("x"))
        return assertThrows(LlmException::class.java) { runBlocking { backend.complete("m", "p", maxTokens = 5) } }
    }

    @Test
    fun serverErrorsAreTransientClientErrorsAreNot() {
        assertTrue(failureFor(500).transient)
        assertTrue(failureFor(503).transient)
        assertFalse(failureFor(400).transient) // z. B. Kontext zu lang: Wiederholen hilft nicht
        assertFalse(failureFor(401).transient)
        assertFalse(failureFor(404).transient)
        server.shutdown()
    }

    @Test
    fun unreachableServerIsTransient() {
        val dead = LmStudioBackend("http://127.0.0.1:1")
        val failure = assertThrows(LlmException::class.java) { runBlocking { dead.complete("m", "p", maxTokens = 5) } }
        assertTrue(failure.transient)
    }
}
