package app.aitranslator.io

import app.aitranslator.core.Block
import app.aitranslator.core.BlockKind
import app.aitranslator.core.Chapter
import app.aitranslator.core.Languages
import app.aitranslator.core.chapterLevel
import app.aitranslator.core.inlineRuns
import app.aitranslator.core.parseBlocks
import app.aitranslator.core.splitChapters
import app.aitranslator.core.stripInline
import app.aitranslator.core.toMarkdown
import app.aitranslator.core.toPlainText
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    TXT("txt", "text/plain", "Text (.txt)"),
    MD("md", "text/markdown", "Markdown (.md)"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)"),
    PDF("pdf", "application/pdf", "PDF (.pdf)"),
    EPUB("epub", "application/epub+zip", "E-Book (.epub)"),
}

/**
 * Export in TXT, MD, DOCX, PDF und EPUB. Überschriften werden echte Überschriften (DOCX-Formatvorlagen,
 * EPUB-Kapitel mit Inhaltsverzeichnis), Absätze bleiben Absätze, **fett** und *kursiv* bleiben erhalten.
 */
object Exporters {
    fun export(format: ExportFormat, content: String, title: String, language: String, out: OutputStream) {
        val blocks = parseBlocks(content)
        when (format) {
            ExportFormat.TXT -> out.write(toPlainText(blocks).toByteArray(Charsets.UTF_8))
            ExportFormat.MD -> out.write(toMarkdown(blocks).toByteArray(Charsets.UTF_8))
            ExportFormat.DOCX -> out.write(docx(blocks, title))
            ExportFormat.EPUB -> out.write(epub(blocks, title, Languages.normalizeCode(language)))
            ExportFormat.PDF -> PdfExporter.write(blocks, title, out)
        }
        out.flush()
    }

    // --- Gemeinsames -------------------------------------------------------------------------

    /** Entfernt in XML unzulässige Steuerzeichen und maskiert die Sonderzeichen. */
    internal fun xmlEscape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun ZipOutputStream.add(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // --- DOCX --------------------------------------------------------------------------------

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    internal fun docx(blocks: List<Block>, title: String): ByteArray {
        val level = chapterLevel(blocks)
        val body = StringBuilder()
        blocks.forEachIndexed { i, block ->
            if (block.kind == BlockKind.HEADING) {
                val pageBreak = if (i > 0 && level > 0 && block.level <= level) "<w:pageBreakBefore/>" else ""
                body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading${block.level}\"/>$pageBreak</w:pPr>")
                    .append("<w:r><w:t xml:space=\"preserve\">${xmlEscape(stripInline(block.text))}</w:t></w:r></w:p>")
                return@forEachIndexed
            }
            body.append("<w:p>")
            for (run in inlineRuns(block.text)) {
                val props = (if (run.bold) "<w:b/>" else "") + (if (run.italic) "<w:i/>" else "")
                run.text.split("\n").forEachIndexed { n, line ->
                    body.append("<w:r>")
                    if (props.isNotEmpty()) body.append("<w:rPr>$props</w:rPr>")
                    if (n > 0) body.append("<w:br/>")
                    body.append("<w:t xml:space=\"preserve\">${xmlEscape(line)}</w:t></w:r>")
                }
            }
            body.append("</w:p>")
        }

        val styles = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"$W_NS\">" +
                "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>" +
                "<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/>" +
                "</w:pPr></w:pPrDefault></w:docDefaults>" +
                "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>",
        )
        val sizes = mapOf(1 to 40, 2 to 32, 3 to 28, 4 to 24, 5 to 22, 6 to 22) // halbe Punkt
        for (n in 1..6) {
            styles.append(
                "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/>" +
                    "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/>" +
                    "<w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"${sizes.getValue(n)}\"/></w:rPr></w:style>",
            )
        }
        styles.append("</w:styles>")

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.add(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
            )
            zip.add(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
            )
            zip.add(
                "word/_rels/document.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            )
            zip.add("word/styles.xml", styles.toString())
            zip.add(
                "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"$W_NS\"><w:body>$body</w:body></w:document>",
            )
            zip.add(
                "docProps/core.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>${xmlEscape(title)}</dc:title></cp:coreProperties>",
            )
        }
        return bytes.toByteArray()
    }

    // --- EPUB --------------------------------------------------------------------------------

    private fun chapterHtml(chapter: Chapter, hasHeadings: Boolean, language: String): String {
        val body = StringBuilder()
        if (!hasHeadings) body.append("<h1>${xmlEscape(chapter.title)}</h1>") // Dokument ohne Überschriften
        for (block in chapter.blocks) {
            if (block.kind == BlockKind.HEADING) {
                body.append("<h${block.level}>${xmlEscape(stripInline(block.text))}</h${block.level}>")
            } else {
                body.append("<p>")
                for (run in inlineRuns(block.text)) {
                    val escaped = xmlEscape(run.text).replace("\n", "<br/>")
                    body.append(if (run.bold) "<strong>$escaped</strong>" else if (run.italic) "<em>$escaped</em>" else escaped)
                }
                body.append("</p>")
            }
        }
        if (body.isEmpty()) body.append("<p></p>")
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$language\" lang=\"$language\"><head><meta charset=\"utf-8\"/>" +
            "<title>${xmlEscape(chapter.title)}</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>" +
            "<body>$body</body></html>"
    }

    internal fun epub(blocks: List<Block>, title: String, language: String): ByteArray {
        val hasHeadings = blocks.any { it.kind == BlockKind.HEADING }
        val chapters = splitChapters(blocks, title)
        val modified = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val id = "urn:uuid:${UUID.randomUUID()}"
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII) // muss als erster Eintrag unkomprimiert stehen
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().also { it.update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            zip.add(
                "META-INF/container.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
            )
            val manifest = StringBuilder()
            val spine = StringBuilder()
            val navItems = StringBuilder()
            val ncxPoints = StringBuilder()
            chapters.forEachIndexed { i, chapter ->
                val n = i + 1
                zip.add("OEBPS/chap_$n.xhtml", chapterHtml(chapter, hasHeadings, language))
                manifest.append("<item id=\"chap$n\" href=\"chap_$n.xhtml\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"chap$n\"/>")
                navItems.append("<li><a href=\"chap_$n.xhtml\">${xmlEscape(chapter.title)}</a></li>")
                ncxPoints.append(
                    "<navPoint id=\"np$n\" playOrder=\"$n\"><navLabel><text>${xmlEscape(chapter.title)}</text></navLabel><content src=\"chap_$n.xhtml\"/></navPoint>",
                )
            }
            zip.add("OEBPS/style.css", "body{font-family:serif;line-height:1.4}p{margin:0 0 .8em 0}h1,h2,h3{margin:1.2em 0 .6em 0}")
            zip.add(
                "OEBPS/nav.xhtml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" " +
                    "xml:lang=\"$language\" lang=\"$language\"><head><meta charset=\"utf-8\"/><title>${xmlEscape(title)}</title></head><body>" +
                    "<nav epub:type=\"toc\" id=\"toc\"><ol>$navItems</ol></nav></body></html>",
            )
            zip.add(
                "OEBPS/toc.ncx",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head>" +
                    "<meta name=\"dtb:uid\" content=\"$id\"/></head><docTitle><text>${xmlEscape(title)}</text></docTitle><navMap>$ncxPoints</navMap></ncx>",
            )
            zip.add(
                "OEBPS/content.opf",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"bookid\">$id</dc:identifier>" +
                    "<dc:title>${xmlEscape(title)}</dc:title><dc:language>$language</dc:language>" +
                    "<meta property=\"dcterms:modified\">$modified</meta></metadata><manifest>" +
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                    "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                    "<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>$manifest</manifest><spine toc=\"ncx\">$spine</spine></package>",
            )
        }
        return bytes.toByteArray()
    }
}
