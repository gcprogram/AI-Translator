package app.aitranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aitranslator.core.MAX_CHUNK_CHARS
import app.aitranslator.core.MIN_CHUNK_CHARS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val config = state.config
    var ip by remember { mutableStateOf(config.ip) }
    var port by remember { mutableStateOf(config.port) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var chunk by remember { mutableStateOf(config.maxChunkChars.toString()) }
    var stream by remember { mutableStateOf(config.stream) }
    var useContext by remember { mutableStateOf(config.useContext) }
    var prompt by remember { mutableStateOf(config.translationPrompt) }
    var modelMenu by remember { mutableStateOf(false) }

    // "Modelle laden" wählt bei leerem Feld das erste Modell; die lokale Eingabe muss das übernehmen
    LaunchedEffect(config.model) { if (model.isBlank()) model = config.model }

    fun save() {
        val chars = chunk.toIntOrNull()
        if (chars == null) {
            vm.showMessage("Teilstücklänge muss eine ganze Zahl sein.")
            return
        }
        vm.saveConfig(
            vm.state.value.config.copy(
                ip = ip, port = port, apiKey = apiKey, model = model.trim(), maxChunkChars = chars,
                stream = stream, useContext = useContext, translationPrompt = prompt,
            ),
        )
        onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Backend: LM Studio im Netzwerk (HTTP, OpenAI-kompatibel)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(ip, { ip = it }, label = { Text("IP-Adresse des PCs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                apiKey, { apiKey = it }, label = { Text("API-Schlüssel (LM Studio: Developer-Einstellungen)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            // Reine Auswahl mit Pfeil im Feld (keine Tastatur); die Liste kommt über "Modelle laden"
            ExposedDropdownMenuBox(
                expanded = modelMenu,
                onExpandedChange = { if (config.models.isNotEmpty()) modelMenu = it },
            ) {
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, label = { Text("Modell") }, singleLine = true,
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu) },
                    supportingText = if (config.models.isEmpty()) ({ Text("Erst „Modelle laden“ antippen") }) else null,
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    config.models.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) }, onClick = { model = name; modelMenu = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { vm.loadModels(ip, port, apiKey) }, enabled = !state.modelsLoading) {
                Text(if (state.modelsLoading) "Lade…" else "Modelle laden")
            }
            OutlinedTextField(
                chunk, { chunk = it }, label = { Text("Teilstücklänge in Zeichen ($MIN_CHUNK_CHARS–$MAX_CHUNK_CHARS)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Übersetzung beim Entstehen anzeigen", stream) { stream = it }
            SwitchRow("Vorgängerabschnitt als Kontext mitsenden", useContext) { useContext = it }
            OutlinedTextField(
                prompt, { prompt = it }, label = { Text("Prompt (nicht TranslateGemma)") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
