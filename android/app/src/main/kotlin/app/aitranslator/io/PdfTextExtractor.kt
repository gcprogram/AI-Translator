package app.aitranslator.io

import app.aitranslator.core.PdfLine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Liest die Zeilen eines PDF samt Schriftgröße und Position. Voraussetzung auf dem Gerät:
 * `PDFBoxResourceLoader.init(context)` wurde einmal aufgerufen (macht die Application).
 */
object PdfTextExtractor {
    fun extractLines(bytes: ByteArray): List<PdfLine> {
        val collector = LineCollector()
        PDDocument.load(ByteArrayInputStream(bytes)).use { document -> collector.getText(document) }
        return collector.lines
    }

    /** PDFBox ruft writeString je Wort auf; hier werden die Wörter einer Zeile wieder zusammengesetzt. */
    private class LineCollector : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()
        private val text = StringBuilder()
        private var sizeSum = 0f
        private var chars = 0
        private var y = 0f
        private var pageHeight = 0f

        init {
            sortByPosition = true
        }

        override fun writeString(string: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || string.isBlank()) return
            val first = textPositions.first()
            val wordSize = textPositions.map { it.fontSizeInPt }.average().toFloat()
            if (text.isNotEmpty() && abs(first.yDirAdj - y) > wordSize * 0.5f) flushLine() // neue Zeile ohne Trenner
            if (text.isEmpty()) {
                y = first.yDirAdj
                pageHeight = first.pageHeight
            } else {
                text.append(' ')
            }
            text.append(string)
            sizeSum += wordSize * string.length
            chars += string.length
        }

        override fun writeLineSeparator() {
            flushLine()
            super.writeLineSeparator()
        }

        override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
            flushLine()
            super.endPage(page)
        }

        private fun flushLine() {
            if (text.isEmpty()) return
            lines.add(PdfLine(text.toString(), sizeSum / chars.coerceAtLeast(1), y, pageHeight, currentPageNo))
            text.setLength(0)
            sizeSum = 0f
            chars = 0
        }
    }
}
