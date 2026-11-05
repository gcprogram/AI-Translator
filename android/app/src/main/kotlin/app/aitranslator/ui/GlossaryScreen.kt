package app.aitranslator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aitranslator.core.Config
import app.aitranslator.core.formatGlossary
import app.aitranslator.core.parseGlossary
import kotlinx.coroutines.launch

/**
 * Glossar: Begriffe mit fester Übersetzung (z. B. Zaubertranknamen). Es gibt mehrere benannte Glossare, etwa eines
 * je Buchreihe; das ausgewählte gilt für die nächste Übersetzung. Änderungen werden sofort gespeichert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(state: UiState, vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val store = vm.glossaries
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val current = state.config.glossaryName
    var names by remember { mutableStateOf(store.names()) }
    var entries by remember(current) { mutableStateOf(store.load(current)) }
    var term by remember { mutableStateOf("") }
    var translation by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun persist(map: Map<String, String>) {
        entries = map
        store.save(current, map)
        names = store.names()
    }

    fun select(name: String) {
        vm.saveConfig(vm.state.value.config.copy(glossaryName = name))
    }

    fun apply() {
        val t = term.trim()
        val tr = translation.trim()
        if (t.isEmpty() || tr.isEmpty()) return
        persist(entries.filterKeys { it.lowercase() != t.lowercase() } + (t to tr))
        term = ""
        translation = ""
    }

    fun merge(added: Map<String, String>) {
        if (added.isEmpty()) {
            scope.launch { snackbar.showSnackbar("Keine Zeilen 'Begriff: Übersetzung' gefunden.") }
            return
        }
        val addedKeys = added.keys.map { it.lowercase() }.toSet()
        persist(entries.filterKeys { it.lowercase() !in addedKeys } + added)
        scope.launch { snackbar.showSnackbar("${added.size} Begriffe übernommen.") }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            merge(parseGlossary(text))
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(formatGlossary(entries).toByteArray(Charsets.UTF_8)) }
            scope.launch { snackbar.showSnackbar("Glossar exportiert.") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Glossar") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Glossar: $current") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        names.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { menuOpen = false; select(name) }) }
                    }
                }
                OutlinedButton(onClick = { showNew = true }) { Text("Neu…") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Löschen") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(term, { term = it }, label = { Text("Begriff") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(translation, { translation = it }, label = { Text("Übersetzung") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::apply, enabled = term.isNotBlank() && translation.isNotBlank()) { Text("Übernehmen") }
                OutlinedButton(onClick = { merge(parseGlossary(clipboard.getText()?.text.orEmpty())) }) { Text("Liste einfügen") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importieren…") }
                OutlinedButton(onClick = { exportLauncher.launch("$current.txt") }, enabled = entries.isNotEmpty()) { Text("Exportieren…") }
            }
            HorizontalDivider()
            if (entries.isEmpty()) {
                Text("Noch keine Begriffe. Feste Übersetzungen (z. B. Namen, Zaubertränke) gelten für alle Modelle.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries.keys.sortedBy { it.lowercase() }, key = { it }) { key ->
                    Row(
                        Modifier.fillMaxWidth().clickable { term = key; translation = entries.getValue(key) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$key  →  ${entries.getValue(key)}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { persist(entries - key) }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("Neues Glossar") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name, z. B. Harry Potter") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { showNew = false; store.save(name.trim(), emptyMap()); names = store.names(); select(name.trim()) },
                ) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Glossar löschen") },
            text = { Text("Das Glossar „$current“ mit ${entries.size} Begriffen wird gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.delete(current)
                    names = store.names()
                    select(Config.DEFAULT_GLOSSARY)
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
