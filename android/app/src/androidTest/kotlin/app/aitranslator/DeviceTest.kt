package app.aitranslator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aitranslator.core.Config
import app.aitranslator.data.ConfigStore
import app.aitranslator.data.GlossaryStore
import app.aitranslator.data.SectionData
import app.aitranslator.data.SessionData
import app.aitranslator.data.SessionStore
import app.aitranslator.io.DocumentReader
import app.aitranslator.io.ExportFormat
import app.aitranslator.io.Exporters
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Läuft auf einem Gerät/Emulator: PDF-Export (android.graphics.pdf) und PDF-Import (PDFBox) brauchen Android. */
@RunWith(AndroidJUnit4::class)
class PdfRoundTripTest {
    private fun pdf(content: String): ByteArray =
        ByteArrayOutputStream().also { Exporters.export(ExportFormat.PDF, content, "Test", "de", it) }.toByteArray()

    @Test
    fun exportThenReadKeepsHeadingsAndText() {
        val filler = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr. ".repeat(40)
        val bytes = pdf(
            "# Das Buch\n\nVorwort mit *Kursiv*.\n\n# Kapitel 1\n\nErster **fetter** Absatz. $filler\n\n" +
                "## Unterkapitel\n\nText.\n\n# Kapitel 2\n\nSchluss & <Ende>.",
        )
        assertEquals("%PDF", String(bytes.copyOf(4)))
        PDDocument.load(bytes.inputStream()).use { assertTrue("Kapitel beginnen auf neuer Seite", it.numberOfPages >= 3) }

        val text = DocumentReader.read("o.pdf", bytes)
        assertTrue(text, text.startsWith("# Das Buch"))
        assertTrue(text, "\n\n# Kapitel 1\n\n" in text)
        assertTrue(text, "\n\n## Unterkapitel\n\n" in text)
        assertTrue(text, "\n\n# Kapitel 2\n\nSchluss & <Ende>." in text)
        assertTrue("Fließtext ist kein Heading", "# Erster" !in text && "# Lorem" !in text)
    }

    @Test
    fun longParagraphSpansPagesWithoutLosingWords() {
        val words = (1..1500).joinToString(" ") { "wort$it" }
        val text = DocumentReader.read("o.pdf", pdf(words))
        assertEquals(words.split(" "), text.split(Regex("\\s+")))
    }

    @Test
    fun ligaturesDoNotBreakTextSearch() {
        val text = DocumentReader.read("o.pdf", pdf("Wir finden den Fluss, Waffeln und Affen auf dem Floß."))
        assertTrue(text, "finden" in text && "Fluss" in text && "Waffeln" in text && "Affen" in text)
    }

    @Test
    fun unicodeSurvivesTheRoundTrip() {
        val text = DocumentReader.read("o.pdf", pdf("Čeština, Привет, ελληνικά, Größe, çağrı."))
        assertTrue(text, "Čeština" in text && "Привет" in text && "ελληνικά" in text && "Größe" in text)
    }
}

@RunWith(AndroidJUnit4::class)
class StoresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun configRoundTripAndCorruptFallback() {
        val store = ConfigStore(context)
        store.save(Config(ip = "10.0.0.5", model = "google.translategemma-4b-it", maxChunkChars = 10))
        val loaded = store.load()
        assertEquals("10.0.0.5", loaded.ip)
        assertEquals(500, loaded.maxChunkChars) // wird beim Speichern bereinigt
        context.getSharedPreferences("settings", 0).edit().putString("config", "{kaputt").commit()
        assertEquals(Config().port, store.load().port)
        context.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    @Test
    fun glossariesAreSeparateFilesAndNamesAreSafe() {
        val dir = File(context.cacheDir, "glossaries-test").apply { deleteRecursively() }
        val store = GlossaryStore(dir)
        assertTrue(Config.DEFAULT_GLOSSARY in store.names())
        store.save("Harry Potter", mapOf("Polyjuice Potion" to "Vielsafttrank"))
        store.save("../böse/Name", mapOf("a" to "b"))
        assertEquals(mapOf("Polyjuice Potion" to "Vielsafttrank"), store.load("Harry Potter"))
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir }) // kein Ausbruch aus dem Ordner
        store.delete("Harry Potter")
        assertEquals(emptyMap<String, String>(), store.load("Harry Potter"))
        dir.deleteRecursively()
    }

    @Test
    fun sessionSurvivesAndBrokenFileIsIgnored() {
        val file = File(context.cacheDir, "session-test.json").apply { delete() }
        val store = SessionStore(file)
        assertNull(store.load())
        store.save(SessionData("buch.epub", listOf(SectionData(1, "Hello.", "Hallo.", listOf("Hinweis")))))
        assertEquals("Hallo.", store.load()!!.sections.single().translation)
        file.writeText("{kaputt")
        assertNull(store.load())
        store.clear()
        assertTrue(!file.exists())
        assertNotNull(Config())
    }
}
