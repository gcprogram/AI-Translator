package app.aitranslator.io

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
