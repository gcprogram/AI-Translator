package app.aitranslator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aitranslator.core.Languages
import app.aitranslator.core.formatGlossary
import app.aitranslator.core.imageIds
import app.aitranslator.io.ExportFormat
import kotlinx.coroutines.launch

private enum class Screen { MAIN, SETTINGS, GLOSSARY }

@Composable
fun TranslatorApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Erststart ohne Einrichtung: gleich in die Einstellungen (rememberSaveable: nur beim ersten Aufbau, nicht nach Drehen)
    var screen by rememberSaveable { mutableStateOf(if (state.config.model.isBlank()) Screen.SETTINGS else Screen.MAIN) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }

    // Ab Android 13 braucht die Benachrichtigung des Vordergrunddienstes eine Erlaubnis; ohne läuft die Übersetzung trotzdem
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            // Lang und mit Schließen-Knopf: Fehlermeldungen (Server, Datei) dürfen nicht zu übersehen sein
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.consumeMessage()
        }
    }
    // Während der Übersetzung soll der Bildschirm anbleiben, sonst unterbricht Android die Verbindung zum Server.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    when (screen) {
        Screen.MAIN -> MainScreen(state, vm, snackbar, onSettings = { screen = Screen.SETTINGS }, onGlossary = { screen = Screen.GLOSSARY })
        Screen.SETTINGS -> SettingsScreen(state, vm, snackbar, onBack = { screen = Screen.MAIN })
        Screen.GLOSSARY -> GlossaryScreen(state, vm, snackbar, onBack = { screen = Screen.MAIN })
    }

    state.glossaryReview?.let { proposal -> GlossaryReviewDialog(proposal, onDone = vm::confirmGlossaryReview) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: UiState, vm: MainViewModel, snackbar: SnackbarHostState,
    onSettings: () -> Unit, onGlossary: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var pendingOpen by remember { mutableStateOf<Uri?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val current = vm.state.value
            if (current.dirty && current.sections.isNotEmpty()) pendingOpen = uri else vm.translateDocument(uri)
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        pendingFormat?.let { format -> if (uri != null) vm.export(format, uri) }
        pendingFormat = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI-Translator")
                        if (state.documentName.isNotEmpty()) {
                            Text(state.documentName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Speichern…") }, enabled = state.sections.isNotEmpty() && !state.running,
                            onClick = { menuOpen = false; showFormats = true })
                        DropdownMenuItem(text = { Text("Suchen und Ersetzen…") }, enabled = state.sections.isNotEmpty(),
                            onClick = { menuOpen = false; showFind = true })
                        DropdownMenuItem(text = { Text("Glossar…") }, onClick = { menuOpen = false; onGlossary() })
                        DropdownMenuItem(text = { Text("Einstellungen…") }, onClick = { menuOpen = false; onSettings() })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LanguageDropdown(state.config.langSource, enabled = !state.running, modifier = Modifier.weight(1f)) {
                    vm.setLanguages(it, state.config.langTarget)
                }
                Text("→")
                LanguageDropdown(state.config.langTarget, enabled = !state.running, modifier = Modifier.weight(1f)) {
                    vm.setLanguages(state.config.langSource, it)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    // Ohne Modell käme nach der Dateiwahl nur ein Hinweis: lieber vorher zur Einrichtung führen
                    onClick = { if (state.config.model.isBlank()) showSetup = true else openLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.running, modifier = Modifier.weight(1f),
                ) {
                    Text("Datei übersetzen…")
                }
                if (state.running) {
                    OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
                } else {
                    OutlinedButton(onClick = { showFormats = true }, enabled = state.sections.isNotEmpty()) { Text("Speichern…") }
                }
            }
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())

            if (!state.running && state.pending.isNotEmpty()) ResumeCard(state, onResume = vm::resume)

            if (state.sections.isEmpty() && state.pending.isEmpty()) {
                Text(
                    "Datei wählen (TXT, MD, PDF, DOCX, EPUB), um sie über LM Studio im Netzwerk zu übersetzen. " +
                        "Server, Token und Modell stehen in den Einstellungen.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                SectionList(state, vm)
            }
        }
    }

    if (showFormats) {
        FormatDialog(onDismiss = { showFormats = false }) { format ->
            showFormats = false
            pendingFormat = format
            saveLauncher.launch(vm.suggestedFileName(format))
        }
    }
    if (showFind) {
        FindReplaceDialog(onDismiss = { showFind = false }) { find, replacement, matchCase ->
            val count = vm.replaceAll(find, replacement, matchCase)
            scope.launch { snackbar.showSnackbar(if (count == 0) "Nicht gefunden." else "$count Ersetzung(en).") }
        }
    }
    if (showSetup) {
        AlertDialog(
            onDismissRequest = { showSetup = false },
            title = { Text("Erst einrichten") },
            text = {
                Text(
                    "Bevor eine Datei übersetzt werden kann, braucht die App den Server:\n\n" +
                        "1. IP-Adresse des PCs, Port und API-Schlüssel von LM Studio eintragen\n" +
                        "2. „Modelle laden“ antippen und ein Modell wählen\n" +
                        "3. Speichern",
                )
            },
            confirmButton = { TextButton(onClick = { showSetup = false; onSettings() }) { Text("Zu den Einstellungen") } },
            dismissButton = { TextButton(onClick = { showSetup = false }) { Text("Abbrechen") } },
        )
    }
    pendingOpen?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Ungespeichert") },
            text = { Text("Die aktuelle Übersetzung wurde noch nicht gespeichert und wird ersetzt. Fortfahren?") },
            confirmButton = { TextButton(onClick = { pendingOpen = null; vm.translateDocument(uri) }) { Text("Ersetzen") } },
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text("Abbrechen") } },
        )
    }
}

/** Unterbrochene Übersetzung: Fehlertext im Wortlaut (zum Kopieren) und Fortsetzen beim ersten offenen Abschnitt. */
@Composable
private fun ResumeCard(state: UiState, onResume: () -> Unit) {
    val failed = state.error != null
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (failed) "Die Übersetzung wurde durch einen Fehler unterbrochen." else "Die Übersetzung ist unterbrochen.",
                style = MaterialTheme.typography.titleSmall,
            )
            state.error?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            Text(
                "${state.pending.size} von ${state.total} Abschnitten sind noch offen. Die fertigen bleiben erhalten.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onResume) { Text("Fortsetzen") }
        }
    }
}

@Composable
private fun SectionList(state: UiState, vm: MainViewModel) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) } // mitscrollen, bis der Benutzer selbst scrollt
    val live = state.sections.lastOrNull()?.takeIf { it.live }

    LaunchedEffect(live?.index) { if (live != null) follow = true }
    LaunchedEffect(live?.translation?.length, follow) {
        if (live != null && follow) {
            listState.scrollToItem(state.sections.lastIndex)
            listState.scrollBy(100_000f) // bis ans Ende des wachsenden Textes (wird am Listenende begrenzt)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            // Berührung beobachten, ohne sie zu verbrauchen: danach folgt die Ansicht dem Text nicht mehr
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial)
                follow = false
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.sections, key = { it.index }) { section ->
            SectionCard(
                section, state.total, state.running, onEdit = { vm.editSection(section.index, it) },
                onRetranslate = { vm.retranslate(section.index) }, loadThumbnail = vm::loadThumbnail,
            )
        }
    }
}

@Composable
private fun SectionCard(
    section: SectionUi, total: Int, running: Boolean, onEdit: (String) -> Unit, onRetranslate: () -> Unit,
    loadThumbnail: suspend (Int) -> ImageBitmap?,
) {
    var showSource by rememberSaveable(section.index) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Abschnitt ${section.index}/$total", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "Original aus" else "Original") }
                TextButton(onClick = onRetranslate, enabled = !running) { Text("Neu") }
            }
            section.warnings.forEach {
                Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (showSource) {
                SelectionContainer {
                    Text(section.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            OutlinedTextField(
                value = section.translation, onValueChange = onEdit, readOnly = section.live,
                modifier = Modifier.fillMaxWidth(),
            )
            val imageIds = remember(section.translation) { imageIds(section.translation).distinct() }
            if (imageIds.isNotEmpty()) ImageStrip(imageIds, loadThumbnail)
        }
    }
}

/** Die Bilder, die zu diesem Abschnitt gehören. Ihre Stelle im Text bestimmt der Platzhalter `[[Bild n]]`. */
@Composable
private fun ImageStrip(ids: List<Int>, load: suspend (Int) -> ImageBitmap?) {
    Text(
        "Bilder in diesem Abschnitt. Den Platzhalter [[Bild n]] im Text verschieben, um die Stelle zu ändern.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (id in ids) {
            key(id) {
                val bitmap by produceState<ImageBitmap?>(null, id) { value = load(id) }
                Box(Modifier.height(96.dp).widthIn(min = 64.dp), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, contentDescription = "Bild $id", modifier = Modifier.height(96.dp), contentScale = ContentScale.Fit) }
                        ?: Text("Bild $id", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun LanguageDropdown(code: String, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(Languages.displayName(code), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Languages.displayList.forEach { display ->
                DropdownMenuItem(text = { Text(display) }, onClick = { open = false; onSelect(Languages.codeFromDisplay(display)) })
            }
        }
    }
}

@Composable
private fun FormatDialog(onDismiss: () -> Unit, onPick: (ExportFormat) -> Unit) {
    var selected by remember { mutableStateOf(ExportFormat.DOCX) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speichern als") },
        text = {
            Column {
                ExportFormat.entries.forEach { format ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == format, onClick = { selected = format })
                        TextButton(onClick = { selected = format }) { Text(format.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("Weiter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun FindReplaceDialog(onDismiss: () -> Unit, onReplace: (String, String, Boolean) -> Unit) {
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suchen und Ersetzen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(find, { find = it }, label = { Text("Suchen") }, singleLine = true)
                OutlinedTextField(replacement, { replacement = it }, label = { Text("Ersetzen durch") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(matchCase, { matchCase = it })
                    Text("Groß-/Kleinschreibung beachten")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onReplace(find, replacement, matchCase) }, enabled = find.isNotEmpty()) { Text("Alle ersetzen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun GlossaryReviewDialog(proposal: Map<String, String>, onDone: (String?) -> Unit) {
    var text by remember(proposal) { mutableStateOf(formatGlossary(proposal)) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Glossar prüfen und korrigieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Format pro Zeile: Begriff: Übersetzung", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Übersetzung starten") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Unverändert") } },
    )
}
