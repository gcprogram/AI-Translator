package app.aitranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aitranslator.core.BACKEND_DEVICE
import app.aitranslator.core.BACKEND_LMSTUDIO
import app.aitranslator.core.MAX_CHUNK_CHARS
import app.aitranslator.core.MIN_CHUNK_CHARS
import app.aitranslator.ondevice.OnDeviceBackend

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
    var backend by remember { mutableStateOf(config.backend) }
    var deviceUri by remember { mutableStateOf(config.deviceModelUri) }
    var deviceName by remember { mutableStateOf(config.deviceModelName) }
    val context = LocalContext.current
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Dauerhaften Lesezugriff merken: die Datei wird nicht kopiert, sondern bei jedem Start direkt gelesen
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            deviceUri = uri.toString()
            deviceName = OnDeviceBackend.displayName(context, uri)
        }
    }

    // "Modelle laden" wählt bei leerem Feld das erste Modell; die lokale Eingabe muss das übernehmen
    LaunchedEffect(config.model) { if (model.isBlank()) model = config.model }

    fun save() {
        val chars = chunk.toIntOrNull()
        if (chars == null) {
            vm.showMessage("Teilstücklänge muss eine ganze Zahl sein.")
            return
        }
        if (backend == BACKEND_DEVICE && deviceUri.isBlank()) {
            vm.showMessage("Bitte eine Modelldatei (.gguf) wählen.")
            return
        }
        vm.saveConfig(
            vm.state.value.config.copy(
                backend = backend, deviceModelUri = deviceUri, deviceModelName = deviceName,
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
            Text("Wo soll übersetzt werden?", style = MaterialTheme.typography.labelLarge)
            BackendChoice("LM Studio im Netzwerk (schnell, braucht den PC)", backend == BACKEND_LMSTUDIO) { backend = BACKEND_LMSTUDIO }
            BackendChoice("Modell auf dem Gerät (offline, langsamer)", backend == BACKEND_DEVICE) { backend = BACKEND_DEVICE }
            if (backend == BACKEND_LMSTUDIO) {
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
            } else {
                Text(
                    "TranslateGemma als GGUF-Datei (z. B. google.translategemma-4b-it.Q4_K_S.gguf, etwa 2,4 GB) wählen. Die Datei wird " +
                        "nicht kopiert, sondern direkt von ihrem Ort gelesen. Das Modell braucht rund 3 GB freien Arbeitsspeicher, " +
                        "und die Übersetzung dauert auf dem Handy deutlich länger als am PC.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Modelldatei wählen…") }
                Text(
                    if (deviceName.isBlank()) "Noch keine Datei gewählt." else deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (deviceName.isNotBlank() && "translategemma" !in deviceName.lowercase()) {
                    Text(
                        "Der Name enthält nicht „translategemma“: auf dem Gerät werden nur TranslateGemma-Modelle unterstützt.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                chunk, { chunk = it }, label = { Text("Teilstücklänge in Zeichen ($MIN_CHUNK_CHARS–$MAX_CHUNK_CHARS)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Übersetzung beim Entstehen anzeigen", stream) { stream = it }
            SwitchRow("Vorgängerabschnitt als Kontext mitsenden", useContext) { useContext = it }
            BatteryOptimizationRow()
            OutlinedTextField(
                prompt, { prompt = it }, label = { Text("Prompt (nicht TranslateGemma)") }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

/**
 * Der Vordergrunddienst hält eine laufende Übersetzung bei gesperrtem Bildschirm am Leben. Manche Handys schränken Apps im
 * Ruhezustand trotzdem ein; dann hilft es, die Akku-Optimierung für diese App auszuschalten.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val ignoring = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    Text(
        "Bricht die Übersetzung bei gesperrtem Handy trotzdem ab, hilft es, die Akku-Optimierung für diese App auszuschalten.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(
        onClick = {
            launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        },
        enabled = !ignoring,
    ) { Text(if (ignoring) "Akku-Optimierung ist aus" else "Akku-Optimierung ausschalten") }
}

@Composable
private fun BackendChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        TextButton(onClick = onSelect) { Text(label) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
