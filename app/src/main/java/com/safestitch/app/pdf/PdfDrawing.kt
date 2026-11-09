package com.safestitch.app.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.safestitch.app.qr.QrCodec

/** Shared page geometry, colours and drawing helpers for SafeStitch PDFs (A4, 72 points per inch). */
internal object PdfStyle {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 40f

    val BRAND = Color.rgb(11, 110, 105)
    val PASS = Color.rgb(27, 127, 59)
    val FAIL = Color.rgb(179, 38, 30)
    val NEUTRAL = Color.rgb(107, 114, 128)
    val INK = Color.rgb(28, 32, 31)
    val MUTED = Color.rgb(90, 98, 96)
    val RULE = Color.rgb(214, 222, 220)
    val ZEBRA = Color.rgb(242, 247, 246)

    fun text(size: Float, color: Int = INK, bold: Boolean = false, italic: Boolean = false, mono: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, style)
        }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun stroke(color: Int, width: Float = 0.75f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    fun layout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        maxLines: Int = Int.MAX_VALUE,
    ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
        .setAlignment(alignment)
        .setIncludePad(false)
        .setLineSpacing(1f, 1.05f)
        .setMaxLines(maxLines)
        .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
        .build()

    fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, top: Float) {
        canvas.withTranslation(x, top) { layout.draw(this) }
    }

    /** Draws a crisp vector QR code (one rectangle per horizontal run of dark modules). */
    fun drawQr(canvas: Canvas, text: String, x: Float, y: Float, size: Float) {
        val matrix = QrCodec.encodeModules(text)
        val cell = size / matrix.width
        canvas.drawRect(x, y, x + size, y + size, fill(Color.WHITE))
        val dark = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        for (row in 0 until matrix.height) {
            var col = 0
            while (col < matrix.width) {
                if (!matrix.get(col, row)) {
                    col++
                    continue
                }
                val start = col
                while (col < matrix.width && matrix.get(col, row)) col++
                canvas.drawRect(x + start * cell, y + row * cell, x + col * cell, y + (row + 1) * cell, dark)
            }
        }
    }

    fun badge(canvas: Canvas, label: String, color: Int, rect: RectF) {
        canvas.drawRoundRect(rect, 3f, 3f, fill(color))
        val paint = text(8f, Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, rect.centerX(), baseline, paint)
    }
}

/** Keeps track of the current page and vertical position, and starts new pages as content flows. */
internal class PdfPager(
    private val document: PdfDocument,
    private val footerText: (pageNumber: Int) -> String,
) {
    private var current: PdfDocument.Page? = null
    var pageNumber = 0
        private set
    var y = PdfStyle.MARGIN
    val left = PdfStyle.MARGIN
    val right = PdfStyle.PAGE_WIDTH - PdfStyle.MARGIN
    val width: Float get() = right - left
    private val bottom = PdfStyle.PAGE_HEIGHT - PdfStyle.MARGIN - 22f

    val canvas: Canvas
        get() = (current ?: newPage().let { current!! }).canvas

    fun newPage() {
        finish()
        pageNumber += 1
        current = document.startPage(
            PdfDocument.PageInfo.Builder(PdfStyle.PAGE_WIDTH, PdfStyle.PAGE_HEIGHT, pageNumber).create(),
        )
        y = PdfStyle.MARGIN
    }

    fun remaining(): Float = bottom - y

    /** Starts a new page when [height] does not fit. Returns true if a new page was started. */
    fun ensure(height: Float): Boolean {
        if (current != null && height <= remaining()) return false
        newPage()
        return true
    }

    fun finish() {
        val page = current ?: return
        drawFooter(page.canvas)
        document.finishPage(page)
        current = null
    }

    /** Draws wrapped text, splitting it across pages when it is taller than the space left. */
    fun flowText(text: CharSequence, paint: TextPaint, x: Float = left, width: Float = this.width, spacingAfter: Float = 4f) {
        if (text.isEmpty()) return
        val layout = PdfStyle.layout(text, paint, width.toInt())
        var line = 0
        while (line < layout.lineCount) {
            ensure((layout.getLineBottom(line) - layout.getLineTop(line)).toFloat())
            val top = layout.getLineTop(line)
            var end = line + 1
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= remaining()) end++
            val height = (layout.getLineBottom(end - 1) - top).toFloat()
            canvas.withClip(x, y, x + width, y + height) {
                withTranslation(x, y - top) { layout.draw(this) }
            }
            y += height
            line = end
        }
        y += spacingAfter
    }

    /** Draws a section heading; [keepWithNext] is the height of content that must stay on the same page. */
    fun heading(text: String, size: Float = 13f, keepWithNext: Float = 0f) {
        ensure(size + 26f + keepWithNext)
        y += 6f
        val paint = PdfStyle.text(size, PdfStyle.BRAND, bold = true)
        canvas.drawText(text, left, y + size, paint)
        y += size + 6f
        canvas.drawLine(left, y, right, y, PdfStyle.stroke(PdfStyle.RULE))
        y += 8f
    }

    private fun drawFooter(c: Canvas) {
        val lineY = PdfStyle.PAGE_HEIGHT - PdfStyle.MARGIN - 12f
        c.drawLine(left, lineY, right, lineY, PdfStyle.stroke(PdfStyle.RULE))
        val paint = PdfStyle.text(7.5f, PdfStyle.MUTED)
        val text = TextUtils.ellipsize(footerText(pageNumber), paint, width, TextUtils.TruncateAt.END)
        c.drawText(text, 0, text.length, left, PdfStyle.PAGE_HEIGHT - PdfStyle.MARGIN, paint)
    }
}
