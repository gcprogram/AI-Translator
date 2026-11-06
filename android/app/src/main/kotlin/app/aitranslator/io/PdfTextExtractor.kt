package app.aitranslator.io

import android.graphics.Bitmap
import app.aitranslator.core.ImageCollector
import app.aitranslator.core.PdfLine
import app.aitranslator.core.imageToken
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Liest die Zeilen eines PDF samt Schriftgröße und Position. Mit [ImageCollector] kommen auch Bilder: sie stehen als Zeile
 * `[[Bild n]]` an der Stelle, an der sie auf der Seite sitzen. Voraussetzung auf dem Gerät: `PDFBoxResourceLoader.init(context)`
 * wurde einmal aufgerufen (macht die Application).
 */
object PdfTextExtractor {
    fun extractLines(bytes: ByteArray, images: ImageCollector? = null): List<PdfLine> {
        val collector = LineCollector(images)
        PDDocument.load(ByteArrayInputStream(bytes)).use { document -> collector.getText(document) }
        return collector.lines
    }

    private class FoundImage(val id: Int, val yTop: Float, val pageHeight: Float)

    /** PDFBox ruft writeString je Wort auf; hier werden die Wörter einer Zeile wieder zusammengesetzt. */
    private class LineCollector(private val images: ImageCollector?) : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()
        private val text = StringBuilder()
        private var sizeSum = 0f
        private var chars = 0
        private var y = 0f
        private var pageHeight = 0f
        private var pageStart = 0
        private val pageImages = mutableListOf<FoundImage>()

        init {
            sortByPosition = true
        }

        override fun startPage(page: PDPage?) {
            pageStart = lines.size
            pageImages.clear()
            super.startPage(page)
        }

        /** Bilder werden im Inhaltsstrom der Seite mit dem Operator "Do" gezeichnet; hier merken wir uns Ort und Bild. */
        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            val collector = images
            if (collector != null && operator.name == "Do" && operands.firstOrNull() is COSName) {
                val xobject = runCatching { resources.getXObject(operands[0] as COSName) }.getOrNull()
                if (xobject is PDImageXObject) runCatching { remember(xobject, collector) }
            }
            super.processOperator(operator, operands)
        }

        private fun remember(image: PDImageXObject, collector: ImageCollector) {
            val matrix = graphicsState.currentTransformationMatrix
            val width = matrix.scalingFactorX
            val height = matrix.scalingFactorY
            // Maske, Linie oder Zierpunkt: kein Bild, das zur Übersetzung gehört
            if (image.isStencil || image.width < MIN_PIXELS || image.height < MIN_PIXELS || width < MIN_POINTS || height < MIN_POINTS) return
            val bitmap = image.image ?: return
            val jpeg = image.suffix == "jpg"
            val encoded = ByteArrayOutputStream().also { bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, it) }
            val id = collector.add(encoded.toByteArray(), if (jpeg) "image/jpeg" else "image/png")
            val pageHeightPt = currentPage.mediaBox.height
            pageImages += FoundImage(id, pageHeightPt - (matrix.translateY + height), pageHeightPt) // von oben gemessen wie der Text
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

        override fun endPage(page: PDPage?) {
            flushLine()
            // Bilder nach ihrer Höhe auf der Seite zwischen die Textzeilen mischen: vor die erste Zeile, die weiter unten steht
            for (image in pageImages.sortedBy { it.yTop }) {
                val index = (pageStart until lines.size).firstOrNull { lines[it].y > image.yTop } ?: lines.size
                lines.add(index, PdfLine(imageToken(image.id), 0f, image.yTop, image.pageHeight, currentPageNo))
            }
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

    private const val MIN_PIXELS = 40
    private const val MIN_POINTS = 20f
}
