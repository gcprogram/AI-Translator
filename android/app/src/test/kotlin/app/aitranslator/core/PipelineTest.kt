package app.aitranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ersatz-Backend: [handler] bekommt (letzte Nutzer-Nachricht bzw. Prompt, Temperatur). */
class FakeBackend(private val handler: (String, Double) -> Completion) : LlmBackend {
    data class Call(val kind: String, val prompt: String, val messages: List<ChatMessage>, val temperature: Double, val streamed: Boolean)

    val calls = mutableListOf<Call>()

    override suspend fun listModels(): List<String> = emptyList()

    private suspend fun answer(call: Call, onDelta: ((String) -> Unit)?): Completion {
        calls += call
        val completion = handler(call.prompt, call.temperature)
        if (onDelta != null) {
            for (word in completion.text.split(" ")) {
                coroutineContext.ensureActive() // wie das echte Backend: Abbruch wirkt zwischen den Textstücken
                onDelta("$word ")
            }
        }
        return completion
    }

    override suspend fun complete(model: String, prompt: String, maxTokens: Int, temperature: Double, stop: List<String>?, onDelta: ((String) -> Unit)?) =
        answer(Call("complete", prompt, emptyList(), temperature, onDelta != null), onDelta)

    override suspend fun chat(model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double, onDelta: ((String) -> Unit)?) =
        answer(Call("chat", messages.last().content, messages, temperature, onDelta != null), onDelta)
}

private fun gemmaConfig() = Config(model = "google.translategemma-4b-it", langSource = "en", langTarget = "de").sanitized()

class PipelineTest {
    @Test
    fun gemmaTranslatesEveryChunk() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Übersetzt.", "stop") }
        val chunks = splitIntoChunks("Eins. ".repeat(400), 500)
        val results = TranslationPipeline(backend, gemmaConfig()).translate(chunks).toList()
        assertEquals(chunks.size, results.size)
        assertTrue(results.all { it.warnings.isEmpty() })
        assertTrue(backend.calls.all { it.kind == "complete" })
    }

    @Test
    fun truncatedOutputIsSplitAndRetranslated() = runTest {
        val backend = FakeBackend { prompt, _ ->
            val request = prompt.substringAfterLast("<start_of_turn>user") // ohne mitgesendeten Kontext
            if (Regex("Satz\\.").findAll(request).count() > 70) Completion("x", "length") else Completion("Teil.", "stop")
        }
        val chunk = splitIntoChunks("Satz. ".repeat(100), 5000)[0]
        val result = TranslationPipeline(backend, gemmaConfig()).translateChunk(chunk, emptyMap())
        assertTrue("Abschnitt wegen Token-Limit geteilt und neu übersetzt" in result.warnings)
        assertTrue("Teil." in result.translation)
        assertFalse("x" in result.translation)
    }

    @Test
    fun suspiciousOutputIsRetriedWithTemperatureAndBetterOneWins() = runTest {
        val backend = FakeBackend { _, temperature -> if (temperature == 0.0) Completion("Abgehackt", "stop") else Completion("Vollständig.", "stop") }
        val result = TranslationPipeline(backend, gemmaConfig()).translateChunk(splitIntoChunks("Ein ganzer Satz.", 100)[0], emptyMap())
        assertEquals("Vollständig." to emptyList<String>(), result.translation to result.warnings)
    }

    @Test
    fun buildContextTakesLastAlignedParagraphsWithinBudget() {
        val source = "A1.\n\nA2.\n\nA3."
        val translation = "B1.\n\nB2.\n\nB3."
        assertEquals("A2.\n\nA3." to "B2.\n\nB3.", buildContext(source, translation, budget = 7))
        assertEquals("A3." to "B3.", buildContext(source, translation, budget = 1)) // mindestens ein Absatz
        assertNull(buildContext(source, "B1.\n\nB2.")) // Absatzzahl passt nicht: keine falsche Zuordnung
    }

    @Test
    fun previousChunkIsSentAsContextAndEchoIsRemoved() = runTest {
        val backend = FakeBackend { prompt, _ ->
            val request = prompt.split("<start_of_turn>model")[prompt.split("<start_of_turn>model").size - 2]
            if ("Zweiter Satz" in request) Completion("Eins übersetzt.\n\nZwei übersetzt.", "stop") // Kontext mitübersetzt
            else Completion("Eins übersetzt.", "stop")
        }
        val chunks = splitIntoChunks("Erster Satz.\n\nZweiter Satz.", 15)
        val results = TranslationPipeline(backend, gemmaConfig()).translate(chunks).toList()
        assertEquals(listOf("Eins übersetzt.", "Zwei übersetzt."), results.map { it.translation })
        assertTrue("Erster Satz.<end_of_turn>\n<start_of_turn>model\nEins übersetzt.<end_of_turn>" in backend.calls[1].prompt)
    }

    @Test
    fun identicalAnswerIsNotMistakenForEcho() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Übersetzt.", "stop") }
        val results = TranslationPipeline(backend, gemmaConfig()).translate(splitIntoChunks("Erster Satz.\n\nZweiter Satz.", 15)).toList()
        assertTrue(results.all { it.translation == "Übersetzt." && it.warnings.isEmpty() })
    }

    @Test
    fun contextCanBeSwitchedOff() = runTest {
        val backend = FakeBackend { _, _ -> Completion("X.", "stop") }
        val config = gemmaConfig().copy(useContext = false)
        TranslationPipeline(backend, config).translate(splitIntoChunks("Erster Satz.\n\nZweiter Satz.", 15)).toList()
        assertTrue(backend.calls.all { Regex("<start_of_turn>user").findAll(it.prompt).count() == 1 })
    }

    @Test
    fun glossaryReachesGemmaPromptAndMissingTermIsReported() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Er trank den Trank.", "stop") }
        val chunk = splitIntoChunks("He drank the Polyjuice Potion.", 100)[0]
        val result = TranslationPipeline(backend, gemmaConfig())
            .translateChunk(chunk, mapOf("Polyjuice Potion" to "Vielsafttrank", "Orc" to "Ork"))
        assertTrue("Polyjuice Potion = Vielsafttrank" in backend.calls[0].prompt)
        assertFalse("Orc" in backend.calls[0].prompt) // nur Begriffe, die im Abschnitt vorkommen
        assertEquals(listOf("Glossar nicht eingehalten: Polyjuice Potion → Vielsafttrank"), result.warnings)
        assertEquals(2, backend.calls.size) // Wiederholung mit Temperatur wurde versucht
    }

    @Test
    fun translationKeepsHeadingMarkers() = runTest {
        val backend = FakeBackend { _, _ -> Completion("Der Anfang\n\nEs war einmal.", "stop") }
        val result = TranslationPipeline(backend, gemmaConfig()).translateChunk(splitIntoChunks("# The Start\n\nOnce upon a time.", 100)[0], emptyMap())
        assertEquals("# Der Anfang\n\nEs war einmal.", result.translation)
    }

    @Test
    fun gemmaUsesStoredGlossaryWithoutAskingTheModel() = runTest {
        val backend = FakeBackend { _, _ -> Completion("", "stop") }
        val glossary = TranslationPipeline(backend, gemmaConfig())
            .buildGlossary(splitIntoChunks("Text.", 100), mapOf("Orc" to "Ork")) { error("kein Dialog für TranslateGemma") }
        assertEquals(mapOf("Orc" to "Ork"), glossary)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun chatModelProposesTermsMergesWithStoredAndSendsGlossaryInSystemPrompt() = runTest {
        val replies = ArrayDeque(listOf(Completion("- Orc: Ork", "stop")) + List(5) { Completion("Fertig.", "stop") })
        val backend = FakeBackend { _, _ -> replies.removeFirst() }
        val config = Config(model = "qwen", langSource = "en", langTarget = "de").sanitized()
        val pipeline = TranslationPipeline(backend, config)
        val chunks = splitIntoChunks("The Orc came. ".repeat(3), 100)
        val glossary = pipeline.buildGlossary(chunks, mapOf("Troll" to "Troll")) { it + ("Elf" to "Elfe") }
        assertEquals(mapOf("Troll" to "Troll", "Orc" to "Ork", "Elf" to "Elfe"), glossary)
        assertTrue("German" in backend.calls[0].messages[0].content) // Zielsprache nicht hartkodiert
        pipeline.translate(chunks, glossary).toList()
        val system = backend.calls[1].messages[0].content
        assertTrue("Orc = Ork" in system && "from English to German" in system)
        assertFalse("Troll" in system) // kommt im Text nicht vor
    }

    @Test
    fun streamingPassesDeltasOnlyForFirstAttempt() = runTest {
        val backend = FakeBackend { _, temperature -> if (temperature == 0.0) Completion("Abgehackt", "stop") else Completion("Vollständig.", "stop") }
        val seen = mutableListOf<Pair<Int, String>>()
        val chunk = splitIntoChunks("Ein ganzer Satz.", 100)[0]
        val result = TranslationPipeline(backend, gemmaConfig()).translate(listOf(chunk)) { c, d -> seen += c.index to d }.toList()[0]
        assertEquals(listOf(1 to "Abgehackt "), seen) // Retry wird nicht gestreamt
        assertEquals("Vollständig.", result.translation) // Endergebnis ersetzt den Streamtext
        assertEquals(listOf(true, false), backend.calls.map { it.streamed })
    }

    @Test
    fun withoutCallbackTheBackendIsNotAskedToStream() = runTest {
        val backend = FakeBackend { _, _ -> Completion("X.", "stop") }
        TranslationPipeline(backend, gemmaConfig()).translate(splitIntoChunks("Satz.", 100)).toList()
        assertFalse(backend.calls[0].streamed)
    }

    @Test
    fun cancelWorksInTheMiddleOfAChunk() = runTest {
        val backend = FakeBackend { _, _ -> Completion("eins zwei drei vier.", "stop") }
        val pipeline = TranslationPipeline(backend, gemmaConfig())
        val seen = mutableListOf<String>()
        lateinit var job: Job
        job = launch {
            pipeline.translate(splitIntoChunks("Satz.", 100)) { _, delta ->
                seen += delta
                if (seen.size == 2) job.cancel()
            }.toList()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("eins ", "zwei "), seen) // nach dem Abbruch kommt nichts mehr
    }

    @Test
    fun backendErrorsPropagateInsteadOfBecomingText() = runTest {
        val backend = object : LlmBackend {
            override suspend fun listModels() = emptyList<String>()
            override suspend fun complete(model: String, prompt: String, maxTokens: Int, temperature: Double, stop: List<String>?, onDelta: ((String) -> Unit)?): Completion =
                throw LlmException("Server nicht erreichbar")
            override suspend fun chat(model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double, onDelta: ((String) -> Unit)?): Completion =
                throw LlmException("Server nicht erreichbar")
        }
        val failure = runCatching { TranslationPipeline(backend, gemmaConfig()).translate(splitIntoChunks("Satz.", 100)).toList() }.exceptionOrNull()
        assertTrue(failure is LlmException)
        assertFalse(failure is CancellationException)
    }
}
