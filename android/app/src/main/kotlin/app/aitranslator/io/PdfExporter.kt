package app.aitranslator.io

import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import app.aitranslator.core.Block
import app.aitranslator.core.BlockKind
import app.aitranslator.core.chapterLevel
import app.aitranslator.core.inlineRuns
import app.aitranslator.core.stripInline
import java.io.OutputStream

/**
 * PDF über Androids PdfDocument (A4, Fließtext mit fett/kursiv, Überschriften größer und fett, Kapitel auf neuer
 * Seite). Android bietet dafür keine Lesezeichen; die gibt es nur im Desktop-Programm.
 */
object PdfExporter {
    private const val PAGE_WIDTH = 595 // A4 in Punkt
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 56f
    private const val BODY_SIZE = 11f
    private const val PARAGRAPH_GAP = 8f
    private val HEADING_SIZES = mapOf(1 to 20f, 2 to 16f, 3 to 14f)
    private const val NO_LIGATURES = "'liga' 0, 'clig' 0"

    fun write(blocks: List<Block>, title: String, out: OutputStream) {
        val document = PdfDocument()
        try {
            Paginator(document).render(blocks)
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private class Paginator(private val document: PdfDocument) {
        private val textWidth = (PAGE_WIDTH - 2 * MARGIN).toInt()
        private val bottom = PAGE_HEIGHT - MARGIN
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var y = MARGIN

        private fun newPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
        }

        // Ohne Ligaturen: sonst steht im PDF-Text 'ﬁ' statt 'fi', und Suchen/Kopieren findet "finden" nicht
        private fun bodyPaint() = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_SIZE
            color = 0xFF000000.toInt()
            fontFeatureSettings = NO_LIGATURES
        }

        private fun headingPaint(level: Int) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = HEADING_SIZES[level] ?: 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = 0xFF000000.toInt()
            fontFeatureSettings = NO_LIGATURES
        }

        private fun layoutOf(text: CharSequence, paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()

        fun render(blocks: List<Block>) {
            newPage()
            val chapterLevel = chapterLevel(blocks)
            blocks.forEachIndexed { i, block ->
                if (block.kind == BlockKind.HEADING) {
                    val layout = layoutOf(stripInline(block.text), headingPaint(block.level))
                    if (i > 0 && chapterLevel > 0 && block.level <= chapterLevel) newPage()
                    else if (y + layout.height + 60f > bottom) newPage() // Überschrift nicht allein am Seitenende
                    draw(layout)
                    y += PARAGRAPH_GAP
                } else {
                    draw(layoutOf(styled(block.text), bodyPaint()))
                    y += PARAGRAPH_GAP
                }
            }
            page?.let { document.finishPage(it) }
        }

        private fun styled(text: String): CharSequence {
            val builder = SpannableStringBuilder()
            for (run in inlineRuns(text)) {
                val start = builder.length
                builder.append(run.text)
                val style = when {
                    run.bold && run.italic -> Typeface.BOLD_ITALIC
                    run.bold -> Typeface.BOLD
                    run.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (style != Typeface.NORMAL) builder.setSpan(StyleSpan(style), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return builder
        }

        /** Zeichnet das Layout Zeile für Zeile und beginnt bei Bedarf eine neue Seite. */
        private fun draw(layout: StaticLayout) {
            var first = 0
            while (first < layout.lineCount) {
                var last = first
                while (last + 1 < layout.lineCount &&
                    y + (layout.getLineBottom(last + 1) - layout.getLineTop(first)) <= bottom
                ) last++
                val height = (layout.getLineBottom(last) - layout.getLineTop(first)).toFloat()
                if (y + height > bottom && y > MARGIN) { // nicht einmal eine Zeile passt mehr
                    newPage()
                    continue
                }
                val canvas: Canvas = page!!.canvas
                canvas.save()
                canvas.translate(MARGIN, y - layout.getLineTop(first))
                canvas.clipRect(0f, layout.getLineTop(first).toFloat(), textWidth.toFloat(), layout.getLineBottom(last).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += height
                first = last + 1
                if (first < layout.lineCount) newPage()
            }
        }
    }
}
