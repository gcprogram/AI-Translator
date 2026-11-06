package app.aitranslator

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aitranslator.core.ImageAsset
import androidx.test.platform.app.InstrumentationRegistry
import app.aitranslator.core.Config
import app.aitranslator.data.ChunkData
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
    fun imagesAreEmbeddedAndTextAroundThemSurvives() {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = ByteArrayOutputStream()
        Exporters.export(
            ExportFormat.PDF, "# Titel\n\nVor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.\n\n[[Bild 1]]", "Test", "de", out,
            images = { id -> if (id == 1) ImageAsset(png, "image/png") else null },
        )
        PDDocument.load(out.toByteArray().inputStream()).use { doc ->
            val withImages = doc.pages.count { page -> page.resources.xObjectNames.iterator().hasNext() }
            assertTrue("Bilder sind im PDF eingebettet", withImages >= 1)
        }
        val text = DocumentReader.read("o.pdf", out.toByteArray())
        assertTrue(text, "Vor dem Bild." in text && "Nach dem Bild." in text)
    }

    @Test
    fun undecodableImageIsSkippedInsteadOfBreakingTheExport() {
        val out = ByteArrayOutputStream()
        Exporters.export(
            ExportFormat.PDF, "Text.\n\n[[Bild 1]]\n\nMehr.", "Test", "de", out,
            images = { ImageAsset("<svg/>".toByteArray(), "image/svg+xml") },
        )
        assertTrue("Mehr." in DocumentReader.read("o.pdf", out.toByteArray()))
    }

    @Test
    fun bookmarksFollowTheHeadingsAndPointToTheirPages() {
        val filler = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr. ".repeat(20)
        val bytes = pdf(
            "# Das Buch\n\nVorwort. $filler\n\n# Kapitel 1\n\nText. $filler\n\n## Unterkapitel\n\nMehr. $filler\n\n# Kapitel 2\n\nEnde. $filler",
        )
        PDDocument.load(bytes.inputStream()).use { doc ->
            val outline = doc.documentCatalog.documentOutline
            assertNotNull("PDF hat Lesezeichen", outline)
            val top = outline.children().toList()
            assertEquals(listOf("Das Buch", "Kapitel 1", "Kapitel 2"), top.map { it.title })
            assertEquals(listOf("Unterkapitel"), top[1].children().map { it.title }.toList()) // Ebene 2 hängt unter Kapitel 1
            val pages = top.map { doc.pages.indexOf(it.findDestinationPage(doc)) }
            assertEquals("Kapitel beginnen auf neuen Seiten: " + pages, pages.sorted(), pages)
            assertTrue("Kapitel 2 steht nicht auf Seite 1: $pages", pages.last() >= 2)
        }
    }

    @Test
    fun imagesInAPdfComeBackAtTheirPositionAsPlaceholders() {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val bytes = ByteArrayOutputStream().also {
            Exporters.export(
                ExportFormat.PDF, "Vor dem Bild steht dieser Satz.\n\n[[Bild 1]]\n\nNach dem Bild steht dieser Satz.", "Test", "de", it,
                images = { id -> if (id == 1) ImageAsset(png, "image/png") else null },
            )
        }.toByteArray()

        val collector = app.aitranslator.core.ImageCollector()
        val text = DocumentReader.read("o.pdf", bytes, collector)
        assertEquals("Vor dem Bild steht dieser Satz.\n\n[[Bild 1]]\n\nNach dem Bild steht dieser Satz.", text)
        val image = collector.images.getValue(1)
        assertTrue(image.mime, image.mime == "image/png" || image.mime == "image/jpeg")
        assertEquals(400 to 200, app.aitranslator.core.sniffImageSize(image.bytes)) // Größe und Seitenverhältnis bleiben

        // ohne Sammlung wie bisher: nur Text
        assertEquals("Vor dem Bild steht dieser Satz.\n\nNach dem Bild steht dieser Satz.", DocumentReader.read("o.pdf", bytes))
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
        store.save(
            SessionData(
                "buch.epub", listOf(SectionData(1, "Hello.", "Hallo.", listOf("Hinweis"))),
                pending = listOf(ChunkData(2, "Two."), ChunkData(3, "Three.")), total = 3, error = "Abschnitt 2: Server nicht erreichbar",
            ),
        )
        val loaded = store.load()!!
        assertEquals("Hallo.", loaded.sections.single().translation)
        assertEquals(listOf(2, 3), loaded.pending.map { it.index }) // offene Abschnitte überleben den Neustart
        assertEquals(3, loaded.total)
        assertEquals("Abschnitt 2: Server nicht erreichbar", loaded.error)
        file.writeText("{kaputt")
        assertNull(store.load())
        store.clear()
        assertTrue(!file.exists())
        assertNotNull(Config())
    }
}

/** Prüft die JNI-Brücke zu llama.cpp, ohne ein Modell zu brauchen: Bibliothek lädt, Fehler kommen als klare Meldung. */
@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    /** Ohne die native Bibliothek (Build mit -PnoNative) gibt es nichts zu prüfen: überspringen statt scheitern. */
    @org.junit.Before
    fun requireNativeLibrary() {
        org.junit.Assume.assumeTrue(
            "native Bibliothek nicht gebaut (-PnoNative)",
            runCatching { System.loadLibrary("aitranslator_llama") }.isSuccess,
        )
    }

    @Test
    fun invalidDescriptorIsRejectedWithClearMessage() {
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            app.aitranslator.ondevice.NativeLlama.load(-1, 512, 2)
        }
        assertTrue(failure.message, "lesen" in failure.message.orEmpty())
    }

    @Test
    fun fileThatIsNoGgufIsRejectedWithClearMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val junk = File(context.cacheDir, "kein-modell.gguf").apply { writeBytes(ByteArray(4096) { (it * 7).toByte() }) }
        android.os.ParcelFileDescriptor.open(junk, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                app.aitranslator.ondevice.NativeLlama.load(descriptor.fd, 512, 2)
            }
            assertTrue(failure.message, "geladen" in failure.message.orEmpty())
        }
        junk.delete()
    }
}
