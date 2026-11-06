package app.aitranslator.ondevice

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.aitranslator.core.ChatMessage
import app.aitranslator.core.Completion
import app.aitranslator.core.LlmBackend
import app.aitranslator.core.LlmException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Rückruf für jedes erzeugte Token (Rohbytes). Rückgabe false = Erzeugung beenden. */
fun interface TokenSink {
    fun onPiece(bytes: ByteArray): Boolean
}

internal object NativeLlama {
    init {
        System.loadLibrary("aitranslator_llama")
    }

    /** [fd]: geöffnete Modelldatei. Die Brücke dupliziert ihn, der Aufrufer behält und schließt seinen eigenen. */
    external fun load(fd: Int, nCtx: Int, nThreads: Int): Long
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, sink: TokenSink, stats: IntArray?): Int
    external fun abort(handle: Long)
    external fun free(handle: Long)

    const val STOPPED = 0
    const val LENGTH = 1
    const val ABORTED = 2
}

/**
 * Hält das Modell geladen, solange der Prozess lebt (Laden dauert, "Neu übersetzen" soll nicht jedes Mal warten) und serialisiert
 * die Aufrufe: das Modell erzeugt immer nur einen Text gleichzeitig. Die Datei wird nicht kopiert, sondern über einen
 * Dateideskriptor direkt aus dem gewählten Ort gelesen und eingeblendet (mmap); das spart bei großen Modellen den Speicherplatz.
 */
internal object OnDeviceEngine {
    private class Loaded(val uri: String, val handle: Long, val descriptor: ParcelFileDescriptor)

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    /** Kontextgröße in Token: Prompt plus Antwort. Größer kostet viel Arbeitsspeicher (Zwischenspeicher je Token). */
    const val CONTEXT_TOKENS = 4096

    suspend fun <T> withModel(context: Context, uri: Uri, block: suspend (handle: Long) -> T): T = mutex.withLock {
        val engine = ensureLoaded(context, uri)
        block(engine.handle)
    }

    private suspend fun ensureLoaded(context: Context, uri: Uri): Loaded = withContext(Dispatchers.IO) {
        loaded?.takeIf { it.uri == uri.toString() }?.let { return@withContext it }
        releaseLocked()
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r") ?: throw LlmException("Modelldatei kann nicht geöffnet werden.")
        } catch (e: SecurityException) {
            throw LlmException("Kein Zugriff mehr auf die Modelldatei. Bitte in den Einstellungen neu auswählen.", e)
        } catch (e: java.io.FileNotFoundException) {
            throw LlmException("Modelldatei nicht gefunden. Bitte in den Einstellungen neu auswählen.", e)
        }
        try {
            val handle = NativeLlama.load(descriptor.fd, CONTEXT_TOKENS, threadCount())
            Loaded(uri.toString(), handle, descriptor).also { loaded = it }
        } catch (e: LinkageError) { // UnsatisfiedLinkError beim ersten Zugriff, später NoClassDefFoundError
            descriptor.close()
            throw LlmException("Die Bibliothek für das Modell auf dem Gerät fehlt in dieser App-Version oder passt nicht zum Gerät.", e)
        } catch (e: RuntimeException) {
            descriptor.close()
            throw LlmException("Modell konnte nicht geladen werden: ${e.message}", e)
        }
    }

    /**
     * Gibt Modell und Datei frei, sobald gerade nicht erzeugt wird. Nach einem Lauf aufrufen: das geladene Modell belegt
     * rund 3 GB Arbeitsspeicher, und Android beendet die App sonst im Hintergrund wegen Speichermangels. Das nächste Übersetzen
     * lädt es neu (einige Sekunden).
     */
    suspend fun releaseWhenIdle() = withContext(Dispatchers.IO) { mutex.withLock { releaseLocked() } }

    private fun releaseLocked() {
        loaded?.let {
            NativeLlama.free(it.handle)
            it.descriptor.close()
        }
        loaded = null
    }

    /** Große Kerne nutzen: bei 8 Kernen 4 (meist 1 + 3 schnelle), sonst die Hälfte; alle zu nutzen bremst durch die kleinen Kerne. */
    private fun threadCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
}

/** Übersetzen mit einem Modell auf dem Gerät (llama.cpp). Unterstützt wird der Roh-Prompt von TranslateGemma. */
class OnDeviceBackend(private val context: Context, private val modelUri: Uri) : LlmBackend {

    override suspend fun listModels(): List<String> = listOf(displayName(context, modelUri))

    override suspend fun complete(
        model: String, prompt: String, maxTokens: Int, temperature: Double, stop: List<String>?, onDelta: ((String) -> Unit)?,
    ): Completion = OnDeviceEngine.withModel(context, modelUri) { handle ->
        withContext(Dispatchers.Default) { run(handle, prompt, maxTokens, temperature, stop.orEmpty(), onDelta) }
    }

    override suspend fun chat(
        model: String, messages: List<ChatMessage>, maxTokens: Int, temperature: Double, onDelta: ((String) -> Unit)?,
    ): Completion = throw LlmException("Das Modell auf dem Gerät übersetzt nur mit TranslateGemma (Modellname muss 'translategemma' enthalten).")

    private suspend fun CoroutineScope.run(
        handle: Long, prompt: String, maxTokens: Int, temperature: Double, stops: List<String>, onDelta: ((String) -> Unit)?,
    ): Completion = coroutineScope {
        // Abbrechen der Coroutine bricht die native Schleife ab, auch mitten im Satz
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                NativeLlama.abort(handle)
            }
        }
        val assembler = Utf8Assembler()
        val matcher = StopMatcher(stops)
        val text = StringBuilder()
        val stats = IntArray(1)
        val sink = TokenSink { bytes ->
            val piece = matcher.feed(assembler.push(bytes))
            if (piece.isNotEmpty()) {
                text.append(piece)
                onDelta?.invoke(piece)
            }
            !matcher.stopped && isActive
        }
        val code = try {
            NativeLlama.generate(handle, prompt, maxTokens, temperature.toFloat(), sink, stats)
        } catch (e: IllegalStateException) {
            throw LlmException(e.message ?: "Erzeugen fehlgeschlagen", e)
        } finally {
            watcher.cancel()
        }
        ensureActive()
        val tail = matcher.flush() + if (matcher.stopped) "" else assembler.flush()
        if (tail.isNotEmpty()) {
            text.append(tail)
            onDelta?.invoke(tail)
        }
        val finish = if (matcher.stopped || code == NativeLlama.STOPPED) "stop" else "length"
        Completion(text.toString().trim(), finish, promptTokens = stats[0])
    }

    companion object {
        fun displayName(context: Context, uri: Uri): String =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
    }
}
