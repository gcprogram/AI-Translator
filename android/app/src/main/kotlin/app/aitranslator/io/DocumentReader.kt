package app.aitranslator.io

import app.aitranslator.core.WS
import app.aitranslator.core.joinParagraphs
import app.aitranslator.core.markHeadings
import app.aitranslator.core.markPdfHeadings
import app.aitranslator.core.groupPdfLines
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/** Liest TXT, MD, PDF, DOCX und EPUB und liefert Text mit Markdown-artiger Absatzstruktur. */
object DocumentReader {
    val SUPPORTED_EXTENSIONS = listOf("txt", "md", "pdf", "docx", "epub")
    private val SPACES = Regex("$WS+")

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** [fileName] bestimmt das Format (nur die Endung zählt). Wirft [IllegalArgumentException] bei unbekanntem Format. */
    fun read(fileName: String, bytes: ByteArray): String {
        val text = when (val ext = extensionOf(fileName)) {
            "txt", "md" -> readText(bytes)
            "pdf" -> readPdf(bytes)
            "docx" -> readDocx(bytes)
            "epub" -> readEpub(bytes)
            else -> throw IllegalArgumentException("Dateiformat ${if (ext.isEmpty()) "(ohne Endung)" else ".$ext"} wird nicht unterstützt.")
        }
        return markHeadings(text.trim())
    }

    fun readText(bytes: ByteArray): String {
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(withoutBom)).toString()
        } catch (e: CharacterCodingException) {
            charset("windows-1252").decode(ByteBuffer.wrap(withoutBom)).toString()
        }
    }

    // --- PDF ---------------------------------------------------------------------------------

    private fun readPdf(bytes: ByteArray): String =
        joinParagraphs(markPdfHeadings(groupPdfLines(PdfTextExtractor.extractLines(bytes))))

    // --- DOCX --------------------------------------------------------------------------------

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    internal fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // keine externen Entitäten
        } catch (e: Exception) {
            // Android-Parser kennt das Feature nicht und löst Entitäten ohnehin nicht auf
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun children(node: Node): List<Node> = (0 until node.childNodes.length).map { node.childNodes.item(it) }

    private fun styleNames(entries: Map<String, ByteArray>): Map<String, String> {
        val bytes = entries["word/styles.xml"] ?: return emptyMap()
        val styles = parseXml(bytes).getElementsByTagNameNS(W, "style")
        val names = mutableMapOf<String, String>()
        for (i in 0 until styles.length) {
            val style = styles.item(i) as org.w3c.dom.Element
            val id = style.getAttributeNS(W, "styleId")
            val name = (children(style).firstOrNull { it.localName == "name" } as? org.w3c.dom.Element)?.getAttributeNS(W, "val")
            if (id.isNotEmpty() && !name.isNullOrEmpty()) names[id] = name
        }
        return names
    }

    private fun paragraphText(paragraph: Node): String {
        val sb = StringBuilder()
        fun walk(node: Node) {
            for (child in children(node)) {
                if (child.nodeType != Node.ELEMENT_NODE) continue
                when (child.localName) {
                    "t" -> sb.append(child.textContent)
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                    "pPr", "rPr" -> Unit
                    else -> walk(child)
                }
            }
        }
        walk(paragraph)
        return sb.toString()
    }

    private fun headingPrefix(paragraph: Node, styles: Map<String, String>): String {
        val pPr = children(paragraph).firstOrNull { it.localName == "pPr" } ?: return ""
        val pStyle = children(pPr).firstOrNull { it.localName == "pStyle" } as? org.w3c.dom.Element ?: return ""
        val id = pStyle.getAttributeNS(W, "val")
        val name = (styles[id] ?: id).lowercase()
        if (name == "title") return "# "
        if (name.startsWith("heading")) {
            val level = Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: 1
            return "#".repeat(level.coerceIn(1, 6)) + " "
        }
        return ""
    }

    private fun readDocx(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val documentXml = entries["word/document.xml"] ?: throw IllegalArgumentException("Keine gültige DOCX-Datei (word/document.xml fehlt).")
        val styles = styleNames(entries)
        val body = parseXml(documentXml).getElementsByTagNameNS(W, "body").item(0)
        val blocks = mutableListOf<String>()
        for (item in children(body)) {
            when (item.localName) {
                "tbl" -> for (row in children(item).filter { it.localName == "tr" }) {
                    val cells = children(row).filter { it.localName == "tc" }.map { cell ->
                        children(cell).filter { it.localName == "p" }.joinToString("\n") { paragraphText(it) }.trim()
                    }
                    if (cells.any { it.isNotEmpty() }) blocks.add(cells.joinToString(" | "))
                }
                "p" -> {
                    val text = paragraphText(item).trim()
                    if (text.isNotEmpty()) blocks.add(headingPrefix(item, styles) + text)
                }
            }
        }
        return blocks.joinToString("\n\n")
    }

    // --- EPUB --------------------------------------------------------------------------------

    private fun resolve(baseDir: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val parts = mutableListOf<String>()
        for (segment in (baseDir + decoded).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun readEpub(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val container = entries["META-INF/container.xml"] ?: throw IllegalArgumentException("Keine gültige EPUB-Datei (container.xml fehlt).")
        val opfPath = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser()).selectFirst("rootfile[full-path]")
            ?.attr("full-path") ?: throw IllegalArgumentException("Keine gültige EPUB-Datei (kein Paketdokument).")
        val opf = Jsoup.parse(String(entries[opfPath] ?: throw IllegalArgumentException("Paketdokument $opfPath fehlt."), Charsets.UTF_8), "", Parser.xmlParser())
        val baseDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
        fun isDocument(item: Element) = item.attr("media-type").let { it == "application/xhtml+xml" || it == "text/html" }
        fun isNav(item: Element) = "nav" in item.attr("properties").split(' ')
        var items = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }.filter { isDocument(it) && !isNav(it) }
        if (items.isEmpty()) items = manifest.values.filter { isDocument(it) && !isNav(it) } // kein brauchbarer Spine

        val blocks = mutableListOf<String>()
        for (item in items) {
            val content = entries[resolve(baseDir, item.attr("href"))] ?: continue
            val soup = Jsoup.parse(String(content, Charsets.UTF_8))
            for (tag in soup.select("h1, h2, h3, h4, h5, h6, p, li, blockquote")) {
                if (tag.tagName() == "blockquote" && tag.selectFirst("p") != null) continue // Inhalt kommt über die <p>
                val text = tag.text().replace(SPACES, " ").trim()
                if (text.isEmpty()) continue
                blocks.add(if (tag.tagName().startsWith("h")) "${"#".repeat(tag.tagName().substring(1).toInt())} $text" else text)
            }
        }
        return blocks.joinToString("\n\n")
    }
}
