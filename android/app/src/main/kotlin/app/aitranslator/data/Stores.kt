package app.aitranslator.data

import android.content.Context
import app.aitranslator.core.Config
import app.aitranslator.core.formatGlossary
import app.aitranslator.core.parseGlossary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

/** Einstellungen als JSON in den SharedPreferences. Kaputte Werte führen zu Defaults, nie zum Absturz. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Config = try {
        prefs.getString(KEY, null)?.let { json.decodeFromString<Config>(it).sanitized() } ?: Config()
    } catch (e: Exception) {
        Config()
    }

    fun save(config: Config) {
        prefs.edit().putString(KEY, json.encodeToString(Config.serializer(), config.sanitized())).apply()
    }

    private companion object {
        const val KEY = "config"
    }
}

/** Glossare als Textdateien ('Begriff: Übersetzung'), eine Datei je Name, z. B. je Buchreihe. */
class GlossaryStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    private fun file(name: String) = File(dir, name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().ifEmpty { Config.DEFAULT_GLOSSARY } + ".txt")

    fun names(): List<String> =
        (dir.listFiles { f -> f.extension == "txt" }.orEmpty().map { it.nameWithoutExtension } + Config.DEFAULT_GLOSSARY)
            .distinct().sortedBy { it.lowercase() }

    fun load(name: String): Map<String, String> {
        val file = file(name)
        return if (file.exists()) parseGlossary(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    fun save(name: String, glossary: Map<String, String>) {
        file(name).writeText(formatGlossary(glossary), Charsets.UTF_8)
    }

    fun delete(name: String) {
        file(name).delete()
    }
}

@Serializable
data class SectionData(val index: Int, val source: String, val translation: String, val warnings: List<String> = emptyList())

@Serializable
data class SessionData(val documentName: String = "", val sections: List<SectionData> = emptyList())

/**
 * Sichert die Übersetzung nach jedem Abschnitt und jeder Änderung. Android beendet Apps im Hintergrund jederzeit;
 * so geht beim Zurückkehren nichts verloren.
 */
class SessionStore(private val file: File) {
    fun load(): SessionData? = try {
        if (file.exists()) json.decodeFromString<SessionData>(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    fun save(data: SessionData) {
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(SessionData.serializer(), data), Charsets.UTF_8)
        temp.renameTo(file) // atomar genug: nie eine halb geschriebene Sitzung
    }

    fun clear() {
        file.delete()
    }
}
