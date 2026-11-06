package app.aitranslator.ui

import android.app.Application
import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aitranslator.core.Chunk
import app.aitranslator.TranslationService
import app.aitranslator.core.CONNECTION_RETRY_DELAYS_MS
import app.aitranslator.core.Config
import app.aitranslator.core.Context
import app.aitranslator.core.GlossaryReview
import app.aitranslator.core.ImageCollector
import app.aitranslator.core.etaText
import app.aitranslator.core.LmStudioBackend
import app.aitranslator.core.Languages
import app.aitranslator.core.TranslationPipeline
import app.aitranslator.core.buildContext
import app.aitranslator.core.parseGlossary
import app.aitranslator.core.splitIntoChunks
import app.aitranslator.ondevice.OnDeviceBackend
import app.aitranslator.ondevice.OnDeviceEngine
import app.aitranslator.data.ChunkData
import app.aitranslator.data.ConfigStore
import app.aitranslator.data.GlossaryStore
import app.aitranslator.data.ImageStore
import app.aitranslator.data.SectionData
import app.aitranslator.data.SessionData
import app.aitranslator.data.SessionStore
import app.aitranslator.io.DocumentReader
import app.aitranslator.io.ExportFormat
import app.aitranslator.io.Exporters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Abschnitt in der Oberfläche. [live] = wird gerade übersetzt und ist noch nicht bearbeitbar. */
data class SectionUi(
    val index: Int,
    val source: String,
    val translation: String,
    val warnings: List<String> = emptyList(),
    val live: Boolean = false,
)

data class UiState(
    val config: Config = Config(),
    val documentName: String = "",
    val sections: List<SectionUi> = emptyList(),
    val total: Int = 0,
    val running: Boolean = false,
    val status: String = "Bereit",
    val progress: Float = 0f,
    /** Es gibt eine Übersetzung, die noch nicht exportiert wurde. */
    val dirty: Boolean = false,
    /** Glossar-Vorschlag der KI, den der Benutzer prüfen soll (nur Chat-Modelle). */
    val glossaryReview: Map<String, String>? = null,
    val modelsLoading: Boolean = false,
    /** Einmalige Meldung für die Snackbar. */
    val message: String? = null,
    /** Noch nicht übersetzte Abschnitte (nach Abbruch, Fehler oder Neustart): lassen sich fortsetzen. */
    val pending: List<Chunk> = emptyList(),
    /** Letzter Fehler im Wortlaut; bleibt stehen (und gespeichert), bis fortgesetzt oder neu begonnen wird. */
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    val glossaries = GlossaryStore(File(application.filesDir, "glossaries"))
    private val sessionStore = SessionStore(File(application.filesDir, "session.json"))
    private val images = ImageStore(File(application.filesDir, "images"))

    private val _state = MutableStateFlow(UiState(config = configStore.load()))
    val state: StateFlow<UiState> = _state

    private var job: Job? = null
    private var saveJob: Job? = null
    private var reviewReply: CompletableDeferred<Map<String, String>>? = null

    // Restzeit-Schätzung: bisheriges Tempo (Zeichen pro Zeit) auf den Rest hochgerechnet
    private var etaStartNanos = 0L
    private var etaTotalChars = 0L
    private var etaDoneChars = 0L

    @Volatile
    private var etaSuffix = ""

    init {
        sessionStore.load()?.takeIf { it.sections.isNotEmpty() || it.pending.isNotEmpty() }?.let { session ->
            val total = session.total.takeIf { it > 0 } ?: (session.sections.size + session.pending.size)
            _state.update {
                it.copy(
                    documentName = session.documentName,
                    sections = session.sections.map { s -> SectionUi(s.index, s.source, s.translation, s.warnings) },
                    pending = session.pending.map { c -> Chunk(c.index, c.text) },
                    total = total,
                    progress = session.sections.size.toFloat() / total.coerceAtLeast(1),
                    dirty = true,
                    error = session.error.ifEmpty { null },
                    status = "Letzte Sitzung wiederhergestellt",
                )
            }
        }
    }

    // --- Einstellungen -----------------------------------------------------------------------

    fun saveConfig(config: Config) {
        val clean = config.sanitized()
        configStore.save(clean)
        _state.update { it.copy(config = clean) }
    }

    fun setLanguages(source: String, target: String) = saveConfig(_state.value.config.copy(langSource = source, langTarget = target))

    fun loadModels(ip: String, port: String, apiKey: String) {
        _state.update { it.copy(modelsLoading = true, status = "Lade Modellliste…") }
        viewModelScope.launch {
            try {
                val models = LmStudioBackend("http://${ip.trim()}:${port.trim()}", apiKey).listModels()
                val current = _state.value.config
                saveConfig(current.copy(models = models, model = current.model.ifEmpty { models.firstOrNull().orEmpty() }))
                _state.update { it.copy(status = "${models.size} Modelle geladen") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Modelle laden fehlgeschlagen: ${e.message}") }
            } finally {
                _state.update { it.copy(modelsLoading = false) }
            }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // --- Übersetzung -------------------------------------------------------------------------

    fun translateDocument(uri: Uri) {
        if (_state.value.running) return
        val config = _state.value.config
        if (!config.isConfigured) {
            _state.update { it.copy(message = "Bitte zuerst in den Einstellungen ein Modell wählen.") }
            return
        }
        job = viewModelScope.launch { runDocument(uri, config) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Neue Pipeline; bei einer Verbindungsstörung wird der halb angezeigte Text des Abschnitts zurückgesetzt. */
    private fun newPipeline(config: Config) = TranslationPipeline(
        if (config.isOnDevice) OnDeviceBackend(getApplication(), Uri.parse(config.deviceModelUri))
        else LmStudioBackend(config.baseUrl, config.apiKey),
        config,
        onRetry = { attempt, reason ->
            _state.update { s ->
                s.copy(
                    status = "Verbindung gestört (Wiederholung $attempt/${CONNECTION_RETRY_DELAYS_MS.size}): $reason",
                    sections = s.sections.map { if (it.live) it.copy(translation = "") else it },
                )
            }
        },
    )

    /** Führt einen Übersetzungslauf aus und sorgt für Statusanzeige, Fehlertext, Sicherung und Aufräumen. */
    private suspend fun guarded(startStatus: String, block: suspend () -> Unit) {
        _state.update { it.copy(running = true, status = startStatus, error = null) }
        TranslationService.start(getApplication()) // Vordergrunddienst: läuft auch bei gesperrtem Bildschirm weiter
        try {
            block()
            _state.update { it.copy(status = "Übersetzung abgeschlossen.") }
        } catch (e: CancellationException) {
            markLiveIncomplete("unvollständig (abgebrochen)")
            _state.update { it.copy(status = "Abgebrochen.") }
            throw e
        } catch (e: Exception) {
            val section = _state.value.pending.firstOrNull()?.index
            val detail = (if (section != null) "Abschnitt $section: " else "") + (e.message ?: e.toString())
            markLiveIncomplete("unvollständig (Fehler)")
            _state.update { it.copy(status = "Fehler", error = detail, message = detail) }
        } finally {
            _state.update { it.copy(running = false, glossaryReview = null) }
            reviewReply = null
            TranslationService.stop(getApplication())
            withContext(NonCancellable) {
                saveNow()
                if (_state.value.config.isOnDevice) OnDeviceEngine.releaseWhenIdle() // Arbeitsspeicher zurückgeben
            }
        }
    }

    /** Setzt eine unterbrochene Übersetzung beim ersten noch offenen Abschnitt fort. */
    fun resume() {
        val s = _state.value
        if (s.running || s.pending.isEmpty()) return
        val config = s.config
        if (!config.isConfigured) {
            _state.update { it.copy(message = "Bitte zuerst in den Einstellungen ein Modell wählen.") }
            return
        }
        job = viewModelScope.launch {
            guarded("Setze Übersetzung fort…") {
                val previous = s.sections.lastOrNull { it.index < s.pending.first().index }
                val context = if (config.useContext && previous != null) buildContext(previous.source, previous.translation) else null
                translateChunks(newPipeline(config), s.pending, glossaries.load(config.glossaryName), context)
            }
        }
    }

    private suspend fun translateChunks(
        pipeline: TranslationPipeline, chunks: List<Chunk>, glossary: Map<String, String>, initialContext: Context?,
    ) {
        val config = _state.value.config
        val onDelta: ((Chunk, String) -> Unit)? = if (config.stream) { chunk, delta -> appendDelta(chunk, delta) } else null
        etaStartNanos = System.nanoTime()
        etaTotalChars = chunks.sumOf { it.text.length.toLong() }
        etaDoneChars = 0
        etaSuffix = ""
        pipeline.translate(chunks, glossary, initialContext, onDelta).collect { result ->
            etaDoneChars += result.source.length
            val elapsedMs = (System.nanoTime() - etaStartNanos) / 1_000_000
            etaSuffix = etaText(elapsedMs, etaDoneChars, etaTotalChars)?.let { " · $it" }.orEmpty()
            _state.update { s ->
                val section = SectionUi(result.index, result.source, result.translation, result.warnings)
                val position = s.sections.indexOfFirst { it.index == result.index }
                val done = s.sections.count { it.index != result.index } + 1
                s.copy(
                    sections = if (position < 0) s.sections + section else s.sections.toMutableList().also { it[position] = section },
                    pending = s.pending.filter { it.index != result.index },
                    progress = done.toFloat() / s.total.coerceAtLeast(1),
                    status = "Abschnitt ${result.index}/${s.total} übersetzt$etaSuffix",
                    dirty = true,
                )
            }
            _state.value.let { TranslationService.update(getApplication(), it.status, (it.progress * 100).toInt()) }
            scheduleSave()
        }
    }

    private suspend fun runDocument(uri: Uri, config: Config) = guarded("Extrahiere Text…") {
        val (name, text, collected) = withContext(Dispatchers.IO) { readDocument(uri) }
        if (text.isEmpty()) throw IllegalStateException("Die Datei konnte nicht gelesen werden oder ist leer.")
        val chunks = splitIntoChunks(text, config.maxChunkChars)
        // Erst jetzt ist sicher, dass es etwas Neues gibt: die bisherige Übersetzung wird ersetzt
        _state.update {
            it.copy(documentName = name, total = chunks.size, sections = emptyList(), pending = chunks, progress = 0f, dirty = false)
        }
        withContext(Dispatchers.IO) { images.replaceAll(collected.images) }

        val pipeline = newPipeline(config)
        val stored = glossaries.load(config.glossaryName)
        if (!config.isTranslateGemma) _state.update { it.copy(status = "KI analysiert Begriffe…") }
        val review: GlossaryReview? = if (config.isTranslateGemma) null else { proposal -> awaitGlossaryReview(proposal) }
        val glossary = pipeline.buildGlossary(chunks, stored, review)
        if (!config.isTranslateGemma) withContext(Dispatchers.IO) { glossaries.save(config.glossaryName, glossary) }
        _state.update { it.copy(status = "Glossar: ${glossary.size} Begriffe") }

        translateChunks(pipeline, chunks, glossary, initialContext = null)
    }

    private fun readDocument(uri: Uri): Triple<String, String, ImageCollector> {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Datei kann nicht geöffnet werden.")
        val collector = ImageCollector()
        return Triple(name, DocumentReader.read(name, bytes, collector), collector)
    }

    /** Vorschaubild zu einer Bild-Nummer (kleiner dekodiert), oder null, wenn es sich nicht darstellen lässt. */
    suspend fun loadThumbnail(id: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val asset = images.load(id) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > THUMBNAIL_PIXELS) sample *= 2
        BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    /** Neues Textstück der laufenden Übersetzung ans Ende des Abschnitts hängen (Abschnitt entsteht beim ersten Stück). */
    private fun appendDelta(chunk: Chunk, delta: String) = _state.update { s ->
        val position = s.sections.indexOfFirst { it.index == chunk.index }
        if (position < 0) {
            s.copy(
                sections = s.sections + SectionUi(chunk.index, chunk.text, delta, live = true),
                status = "Abschnitt ${chunk.index}/${s.total} wird übersetzt…$etaSuffix",
            )
        } else {
            s.copy(sections = s.sections.toMutableList().also { it[position] = it[position].copy(translation = it[position].translation + delta) })
        }
    }

    /** Ein unterbrochener Abschnitt bleibt stehen, wird aber in den Hinweisen markiert. */
    private fun markLiveIncomplete(reason: String) = _state.update { s ->
        s.copy(sections = s.sections.map { if (it.live) it.copy(live = false, warnings = it.warnings + reason) else it })
    }

    /** Einen einzelnen Abschnitt neu übersetzen; der Vorgänger aus dem aktuellen Text dient als Kontext. */
    fun retranslate(index: Int) {
        val s = _state.value
        val config = s.config
        val section = s.sections.firstOrNull { it.index == index } ?: return
        if (s.running || !config.isConfigured) return
        val previous = s.sections.firstOrNull { it.index == index - 1 }
        val context = if (config.useContext && previous != null) buildContext(previous.source, previous.translation) else null
        job = viewModelScope.launch {
            _state.update { st ->
                st.copy(running = true, status = "Abschnitt $index wird neu übersetzt…",
                    sections = st.sections.map { if (it.index == index) it.copy(translation = "", warnings = emptyList(), live = true) else it })
            }
            TranslationService.start(getApplication())
            try {
                val pipeline = newPipeline(config)
                val glossary = glossaries.load(config.glossaryName)
                val onDelta: ((String) -> Unit)? = if (config.stream) { delta -> appendDelta(Chunk(index, section.source), delta) } else null
                val result = pipeline.translateChunk(Chunk(index, section.source), glossary, context, onDelta)
                _state.update { st ->
                    st.copy(status = "Abschnitt $index neu übersetzt", dirty = true,
                        sections = st.sections.map { if (it.index == index) SectionUi(index, result.source, result.translation, result.warnings) else it })
                }
            } catch (e: CancellationException) {
                restoreSection(section, "Abgebrochen.")
                throw e
            } catch (e: Exception) {
                restoreSection(section, "Fehler")
                _state.update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(running = false) }
                TranslationService.stop(getApplication())
                withContext(NonCancellable) {
                    saveNow()
                    if (_state.value.config.isOnDevice) OnDeviceEngine.releaseWhenIdle()
                }
            }
        }
    }

    private fun restoreSection(original: SectionUi, status: String) = _state.update { st ->
        st.copy(status = status, sections = st.sections.map { if (it.index == original.index) original else it })
    }

    // --- Glossar-Prüfung (Chat-Modelle) -------------------------------------------------------

    private suspend fun awaitGlossaryReview(proposal: Map<String, String>): Map<String, String> {
        val reply = CompletableDeferred<Map<String, String>>()
        reviewReply = reply
        _state.update { it.copy(glossaryReview = proposal, status = "Glossar prüfen…") }
        return reply.await()
    }

    fun confirmGlossaryReview(text: String?) {
        val proposal = _state.value.glossaryReview ?: return
        _state.update { it.copy(glossaryReview = null) }
        reviewReply?.complete(if (text == null) proposal else parseGlossary(text))
    }

    // --- Bearbeiten --------------------------------------------------------------------------

    fun editSection(index: Int, text: String) {
        _state.update { s -> s.copy(dirty = true, sections = s.sections.map { if (it.index == index && !it.live) it.copy(translation = text) else it }) }
        scheduleSave()
    }

    /** Ersetzt in allen Abschnitten; Rückgabe = Anzahl der Ersetzungen. */
    fun replaceAll(find: String, replacement: String, matchCase: Boolean): Int {
        if (find.isEmpty()) return 0
        val regex = Regex(Regex.escape(find), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        var count = 0
        _state.update { s ->
            count = 0
            s.copy(dirty = true, sections = s.sections.map { section ->
                if (section.live) section else {
                    count += regex.findAll(section.translation).count()
                    section.copy(translation = regex.replace(section.translation, Regex.escapeReplacement(replacement)))
                }
            })
        }
        if (count > 0) scheduleSave()
        return count
    }

    // --- Export ------------------------------------------------------------------------------

    fun suggestedFileName(format: ExportFormat): String {
        val base = _state.value.documentName.substringBeforeLast('.').ifEmpty { "Übersetzung" }
        return "$base (${Languages.normalizeCode(_state.value.config.langTarget)}).${format.extension}"
    }

    fun export(format: ExportFormat, uri: Uri) {
        val s = _state.value
        val content = s.sections.joinToString("\n\n") { it.translation }.trim()
        if (content.isEmpty()) {
            _state.update { it.copy(message = "Es gibt noch keine Übersetzung.") }
            return
        }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Ziel kann nicht geschrieben werden.")
                    out.use {
                        Exporters.export(format, content, s.documentName.substringBeforeLast('.').ifEmpty { "Übersetzung" }, s.config.langTarget, it, images::load)
                    }
                    null
                } catch (e: Exception) {
                    "Speichern fehlgeschlagen: ${e.message ?: e}"
                }
            }
            _state.update { it.copy(message = message ?: "Gespeichert.", dirty = if (message == null) false else it.dirty) }
        }
    }

    // --- Sitzung sichern ---------------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    private suspend fun saveNow() = withContext(Dispatchers.IO) {
        val s = _state.value
        if (s.sections.isEmpty() && s.pending.isEmpty()) {
            sessionStore.clear()
            images.clear()
        } else {
            sessionStore.save(
                SessionData(
                    documentName = s.documentName,
                    sections = s.sections.map { SectionData(it.index, it.source, it.translation, it.warnings) },
                    pending = s.pending.map { ChunkData(it.index, it.text) },
                    total = s.total,
                    error = s.error.orEmpty(),
                ),
            )
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 600L
        const val THUMBNAIL_PIXELS = 480
    }
}
