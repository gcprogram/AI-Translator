package app.aitranslator.io

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Schreibt Lesezeichen (Gliederung) in ein fertiges PDF. Voraussetzung: `PDFBoxResourceLoader.init` wurde aufgerufen. */
internal object PdfBookmarks {
    fun add(pdf: ByteArray, bookmarks: List<PdfExporter.Bookmark>): ByteArray {
        if (bookmarks.isEmpty()) return pdf
        PDDocument.load(ByteArrayInputStream(pdf)).use { document ->
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            // Pfad der offenen Ebenen: (Ebene, Eintrag); ein neuer Eintrag hängt unter dem letzten mit kleinerer Ebene
            val open = mutableListOf<Pair<Int, PDOutlineItem>>()
            for (bookmark in bookmarks) {
                if (bookmark.page < 1 || bookmark.page > document.numberOfPages) continue
                val item = PDOutlineItem().apply {
                    title = bookmark.title
                    destination = PDPageFitDestination().apply { page = document.getPage(bookmark.page - 1) }
                }
                while (open.isNotEmpty() && open.last().first >= bookmark.level) open.removeAt(open.lastIndex)
                val parent: PDOutlineNode = open.lastOrNull()?.second ?: outline
                parent.addLast(item)
                open += bookmark.level to item
            }
            outline.openNode()
            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }
}
