package app.aitranslator.io

import app.aitranslator.core.ImageAsset
import app.aitranslator.core.ImageCollector
import app.aitranslator.core.fakePng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val CONTENT = "# Das Buch\n\nVorwort mit *Kursiv*.\n\n# Kapitel 1\n\nErster **fetter** Absatz.\nZweite Zeile.\n\n" +
    "## Unterkapitel\n\nText.\n\n# Kapitel 2\n\nSchluss & <Ende>."

private fun zip(vararg files: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun export(format: ExportFormat, content: String = CONTENT): ByteArray =
    ByteArrayOutputStream().also { Exporters.export(format, content, "Testbuch", "de", it) }.toByteArray()

class DocumentReaderTest {
    @Test
    fun txtEncodings() {
        assertEquals("Grüße aus Köln", DocumentReader.read("a.txt", "Grüße aus Köln".toByteArray(charset("windows-1252"))))
        assertEquals("Grüße", DocumentReader.read("b.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray()))
        assertEquals("日本語", DocumentReader.read("c.md", "日本語".toByteArray()))
    }

    @Test
    fun unsupportedFormatAndExtensionCase() {
        assertThrows(IllegalArgumentException::class.java) { DocumentReader.read("x.xyz", ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { DocumentReader.read("ohne", ByteArray(0)) }
        assertEquals("Text", DocumentReader.read("GROSS.TXT", "Text".toByteArray()))
    }

    @Test
    fun docxHeadingsViaStyleNamesTablesAndTabs() {
        val document = """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:pStyle w:val="berschrift2"/></w:pPr><w:r><w:t>Titel</w:t></w:r></w:p>
            <w:p><w:r><w:t>Ein </w:t></w:r><w:r><w:t>Absatz</w:t></w:r><w:r><w:tab/><w:t>.</w:t></w:r></w:p>
            <w:p><w:r><w:t></w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val styles = """<?xml version="1.0"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:style w:type="paragraph" w:styleId="berschrift2"><w:name w:val="heading 2"/></w:style></w:styles>"""
        // 'Überschrift 2' heißt in der Datei immer 'heading 2', unabhängig von der Sprache von Word
        assertEquals("## Titel\n\nEin Absatz\t.\n\nA | B", DocumentReader.read("t.docx", zip("word/document.xml" to document, "word/styles.xml" to styles)))
    }

    @Test
    fun brokenDocxGivesClearError() {
        assertThrows(IllegalArgumentException::class.java) { DocumentReader.read("t.docx", zip("foo.txt" to "x")) }
    }

    @Test
    fun epubFollowsSpineOrderSkipsNavAndKeepsHeadingsOnce() {
        val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="b" href="text/b%20zwei.xhtml" media-type="application/xhtml+xml"/>
            <item id="a" href="text/a.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="s.css" media-type="text/css"/></manifest>
            <spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
        val a = """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Eins</h1><p>Erster   Absatz.</p><blockquote><p>Zitat</p></blockquote></body></html>"""
        val b = """<html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Zwei</h2><p>Zweiter Absatz.</p><ul><li>Punkt</li></ul></body></html>"""
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml"><body><nav><ol><li>Eins</li></ol></nav></body></html>"""
        val bytes = zip("META-INF/container.xml" to container, "OPS/book.opf" to opf, "OPS/text/a.xhtml" to a, "OPS/text/b zwei.xhtml" to b, "OPS/nav.xhtml" to nav)
        assertEquals("# Eins\n\nErster Absatz.\n\nZitat\n\n## Zwei\n\nZweiter Absatz.\n\nPunkt", DocumentReader.read("b.epub", bytes))
    }

    @Test
    fun epubWithoutSpineFallsBackToAllDocuments() {
        val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="x"/></rootfiles></container>"""
        val opf = """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest></package>"""
        val bytes = zip("META-INF/container.xml" to container, "book.opf" to opf, "a.xhtml" to "<html><body><p>Nur Text.</p></body></html>")
        assertEquals("Nur Text.", DocumentReader.read("c.epub", bytes))
    }
}

class ExportersTest {
    @Test
    fun txtHasNoMarkupAndMdKeepsIt() {
        val txt = String(export(ExportFormat.TXT))
        assertFalse("#" in txt || "*" in txt)
        assertTrue("Erster fetter Absatz.\nZweite Zeile." in txt)
        assertTrue("## Unterkapitel" in String(export(ExportFormat.MD)))
    }

    @Test
    fun docxIsValidAndRoundtripsHeadingsParagraphsAndEscaping() {
        val bytes = export(ExportFormat.DOCX)
        val read = DocumentReader.read("o.docx", bytes)
        assertEquals(
            "# Das Buch\n\nVorwort mit Kursiv.\n\n# Kapitel 1\n\nErster fetter Absatz.\nZweite Zeile.\n\n## Unterkapitel\n\nText.\n\n# Kapitel 2\n\nSchluss & <Ende>.",
            read,
        )
        val xml = String(unzip(bytes).getValue("word/document.xml"))
        assertTrue("<w:b/>" in xml && "<w:i/>" in xml)
        assertEquals(2, Regex("<w:pageBreakBefore/>").findAll(xml).count()) // Kapitel 1 und 2, nicht der Titel am Anfang
        assertTrue("Heading2" in xml)
    }

    @Test
    fun docxDropsCharactersThatAreInvalidInXml() {
        val read = DocumentReader.read("o.docx", export(ExportFormat.DOCX, "Text\u0001mit\u000bSteuerzeichen."))
        assertEquals("TextmitSteuerzeichen.", read)
    }

    @Test
    fun epubIsWellFormedWithOneChapterPerHeading() {
        val bytes = export(ExportFormat.EPUB)
        // mimetype muss der erste Eintrag sein, unkomprimiert
        ZipInputStream(bytes.inputStream()).use { zip ->
            val first = zip.nextEntry
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", String(zip.readBytes()))
        }
        val files = unzip(bytes)
        val opf = String(files.getValue("OEBPS/content.opf"))
        assertEquals(3, Regex("<itemref idref=\"chap\\d+\"/>").findAll(opf).count()) // Das Buch, Kapitel 1, Kapitel 2
        assertTrue("<dc:language>de</dc:language>" in opf)
        val nav = String(files.getValue("OEBPS/nav.xhtml"))
        assertTrue("Kapitel 1" in nav && "Kapitel 2" in nav)
        val text = DocumentReader.read("o.epub", bytes)
        assertTrue("Schluss & <Ende>." in text)
        assertTrue("## Unterkapitel" in text)
        assertTrue("<strong>fetter</strong>" in String(files.getValue("OEBPS/chap_2.xhtml")))
        // jedes Kapitel ist wohlgeformtes XML
        for (name in files.keys.filter { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") }) {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(files.getValue(name).inputStream())
        }
    }

    @Test
    fun epubWithoutHeadingsGetsTitleAsSingleChapter() {
        val bytes = export(ExportFormat.EPUB, "Nur ein Absatz.\n\nNoch einer.")
        val chapter = String(unzip(bytes).getValue("OEBPS/chap_1.xhtml"))
        assertTrue("<h1>Testbuch</h1>" in chapter)
        assertEquals("# Testbuch\n\nNur ein Absatz.\n\nNoch einer.", DocumentReader.read("o.epub", bytes))
    }
}

// --- Bilder -------------------------------------------------------------------------------------

private fun zipBytes(vararg files: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, content) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun exportWith(format: ExportFormat, content: String, images: Map<Int, ImageAsset>): ByteArray =
    ByteArrayOutputStream().also { Exporters.export(format, content, "Testbuch", "de", it) { id -> images[id] } }.toByteArray()

class EpubImagesTest {
    private val png1 = fakePng(300, 200, 1)
    private val png2 = fakePng(50, 50, 2)
    private val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="x"/></rootfiles></container>"""
    private val opf = """<package xmlns="http://www.idpf.org/2007/opf"><manifest>
        <item id="a" href="text/a.xhtml" media-type="application/xhtml+xml"/>
        <item id="b" href="text/b.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
    private val chapterA = """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Eins</h1><p><img src="../images/one.png" alt="x"/></p>
        <p>Text vor dem Bild.</p><img src="../images/two.png"/><p>Text danach.</p><img src="../images/missing.png"/>
        <img src="https://example.com/remote.png"/><img src="data:image/png;base64,AAAA"/></body></html>"""
    private val chapterB = """<html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Zwei</h2>
        <img src="/OPS/images/one.png"/><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../images/two.png"/></svg>
        <p>Ende.</p></body></html>"""
    private val epub = zipBytes(
        "META-INF/container.xml" to container.toByteArray(), "OPS/book.opf" to opf.toByteArray(),
        "OPS/text/a.xhtml" to chapterA.toByteArray(), "OPS/text/b.xhtml" to chapterB.toByteArray(),
        "OPS/images/one.png" to png1, "OPS/images/two.png" to png2,
    )

    @Test
    fun imagesStandBetweenTheParagraphsWhereTheyWere() {
        val collector = ImageCollector()
        val text = DocumentReader.read("b.epub", epub, collector)
        assertEquals(
            "# Eins\n\n[[Bild 1]]\n\nText vor dem Bild.\n\n[[Bild 2]]\n\nText danach.\n\n## Zwei\n\n[[Bild 1]]\n\n[[Bild 2]]\n\nEnde.",
            text,
        )
        assertEquals(2, collector.images.size) // gleiche Bilder in beiden Kapiteln haben dieselbe Nummer
        assertArrayEquals(png1, collector.images.getValue(1).bytes)
        assertEquals("image/png", collector.images.getValue(2).mime)
    }

    @Test
    fun withoutCollectorImagesAreIgnoredAsBefore() {
        assertEquals("# Eins\n\nText vor dem Bild.\n\nText danach.\n\n## Zwei\n\nEnde.", DocumentReader.read("b.epub", epub))
    }
}

class DocxImagesTest {
    @Test
    fun docxWithImageRoundTrips() {
        val png = fakePng(640, 480, 7)
        val bytes = exportWith(ExportFormat.DOCX, "# Titel\n\nVor dem Bild.\n\n[[Bild 3]]\n\nNach dem Bild.", mapOf(3 to ImageAsset(png, "image/png")))
        val collector = ImageCollector()
        assertEquals("# Titel\n\nVor dem Bild.\n\n[[Bild 1]]\n\nNach dem Bild.", DocumentReader.read("o.docx", bytes, collector))
        assertArrayEquals(png, collector.images.getValue(1).bytes)
    }

    @Test
    fun docxPackageDeclaresImageAndScalesItToTheTextWidth() {
        val files = unzip(exportWith(ExportFormat.DOCX, "[[Bild 1]]", mapOf(1 to ImageAsset(fakePng(4000, 1000), "image/png"))))
        assertTrue("word/media/img1.png" in files)
        assertTrue("Extension=\"png\"" in String(files.getValue("[Content_Types].xml")))
        assertTrue("Target=\"media/img1.png\"" in String(files.getValue("word/_rels/document.xml.rels")))
        val xml = String(files.getValue("word/document.xml"))
        assertTrue("r:embed=\"rIdImg1\"" in xml)
        // 4000 x 1000 px würde 38 Zoll breit: wird auf 6 Zoll (5486400 EMU) verkleinert, Seitenverhältnis bleibt
        assertTrue("cx=\"5486400\" cy=\"1371600\"" in xml)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(files.getValue("word/document.xml").inputStream()) // wohlgeformt
    }

    @Test
    fun formatsWordCannotEmbedAreLeftOut() {
        val files = unzip(exportWith(ExportFormat.DOCX, "Text.\n\n[[Bild 1]]", mapOf(1 to ImageAsset("<svg/>".toByteArray(), "image/svg+xml"))))
        assertTrue(files.keys.none { it.startsWith("word/media/") })
        assertFalse("<w:drawing>" in String(files.getValue("word/document.xml")))
    }

    @Test
    fun missingImageIsSkippedWithoutError() {
        val files = unzip(exportWith(ExportFormat.DOCX, "Text.\n\n[[Bild 99]]", emptyMap()))
        assertFalse("<w:drawing>" in String(files.getValue("word/document.xml")))
    }
}

class EpubExportImagesTest {
    @Test
    fun imagesAreStoredOnceAndReferencedInTheChapter() {
        val png = fakePng(100, 100, 5)
        val bytes = exportWith(
            ExportFormat.EPUB, "# Eins\n\nText.\n\n[[Bild 2]]\n\n# Zwei\n\n[[Bild 2]]\n\nEnde.",
            mapOf(2 to ImageAsset(png, "image/png")),
        )
        val files = unzip(bytes)
        assertArrayEquals(png, files.getValue("OEBPS/images/img_2.png"))
        assertEquals(1, files.keys.count { it.startsWith("OEBPS/images/") })
        val opf = String(files.getValue("OEBPS/content.opf"))
        assertTrue("href=\"images/img_2.png\" media-type=\"image/png\"" in opf)
        assertTrue("<img src=\"images/img_2.png\"" in String(files.getValue("OEBPS/chap_1.xhtml")))
        assertTrue("<img src=\"images/img_2.png\"" in String(files.getValue("OEBPS/chap_2.xhtml")))
        // und lässt sich wieder einlesen, die Bilder an derselben Stelle
        val collector = ImageCollector()
        assertEquals("# Eins\n\nText.\n\n[[Bild 1]]\n\n# Zwei\n\n[[Bild 1]]\n\nEnde.", DocumentReader.read("o.epub", bytes, collector))
        assertEquals(1, collector.images.size)
    }

    @Test
    fun textExportsDropImages() {
        val images = mapOf(1 to ImageAsset(fakePng(10, 10), "image/png"))
        val txt = String(exportWith(ExportFormat.TXT, "Davor.\n\n[[Bild 1]]\n\nDanach.", images))
        assertEquals("Davor.\n\nDanach.\n", txt)
        assertEquals("Davor.\n\nDanach.\n", String(exportWith(ExportFormat.MD, "Davor.\n\n[[Bild 1]]\n\nDanach.", images)))
    }
}

class LineEndingTest {
    @Test
    fun windowsLineEndingsStillGiveParagraphsAndHeadings() {
        val crlf = "Kapitel 1\r\n\r\nDer Anfang\r\n\r\nErster Absatz.\r\n\r\nZweiter Absatz.\r\n"
        val text = DocumentReader.read("w.txt", crlf.toByteArray())
        assertEquals("# Kapitel 1 – Der Anfang\n\nErster Absatz.\n\nZweiter Absatz.", text)
        assertFalse('\r' in text)
    }

    @Test
    fun oldMacLineEndingsAndMixedEndingsAreNormalized() {
        assertEquals("Eins.\n\nZwei.\n\nDrei.", DocumentReader.read("m.txt", "Eins.\r\rZwei.\n\nDrei.".toByteArray()))
    }

    @Test
    fun crlfTextIsSplitIntoRealParagraphsForChunkingAndContext() {
        val text = DocumentReader.read("w.txt", "Satz eins.\r\n\r\nSatz zwei.\r\n\r\nSatz drei.".toByteArray())
        assertEquals(3, app.aitranslator.core.splitParagraphs(text).size)
    }
}
