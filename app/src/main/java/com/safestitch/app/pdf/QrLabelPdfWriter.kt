package com.safestitch.app.pdf

import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.domain.QrPayload
import java.io.File
import java.io.FileOutputStream

/** Printable A4 sheet of QR labels (3 x 4 per page) to stick on checkpoints. */
class QrLabelPdfWriter {
    /** Writes the labels to [out] and returns the number of pages. */
    fun write(checkpoints: List<CheckpointEntity>, out: File, generatedAt: String): Int {
        require(checkpoints.isNotEmpty()) { "No checkpoints to print" }
        val document = PdfDocument()
        try {
            val columns = 3
            val rows = 4
            val top = PdfStyle.MARGIN + 16f
            val cellWidth = (PdfStyle.PAGE_WIDTH - 2 * PdfStyle.MARGIN) / columns
            val cellHeight = (PdfStyle.PAGE_HEIGHT - PdfStyle.MARGIN - top) / rows
            val qrSize = 112f
            val cut = PdfStyle.stroke(PdfStyle.RULE, 0.8f).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
            val namePaint = PdfStyle.text(10f, bold = true)
            val codePaint = PdfStyle.text(9f, PdfStyle.BRAND, bold = true, mono = true)
            val areaPaint = PdfStyle.text(7.5f, PdfStyle.MUTED)
            var pageNumber = 0
            checkpoints.chunked(columns * rows).forEach { chunk ->
                pageNumber += 1
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(PdfStyle.PAGE_WIDTH, PdfStyle.PAGE_HEIGHT, pageNumber).create(),
                )
                val c = page.canvas
                c.drawText(
                    "SafeStitch checkpoint QR labels  ·  $generatedAt  ·  Page $pageNumber",
                    PdfStyle.MARGIN,
                    PdfStyle.MARGIN + 4f,
                    PdfStyle.text(8f, PdfStyle.MUTED),
                )
                chunk.forEachIndexed { index, checkpoint ->
                    val x = PdfStyle.MARGIN + (index % columns) * cellWidth
                    val y = top + (index / columns) * cellHeight
                    c.drawRect(RectF(x + 3f, y + 3f, x + cellWidth - 3f, y + cellHeight - 3f), cut)
                    PdfStyle.drawQr(c, QrPayload.forCheckpoint(checkpoint.code), x + (cellWidth - qrSize) / 2, y + 10f, qrSize)
                    val textWidth = (cellWidth - 16f).toInt()
                    var textTop = y + 10f + qrSize + 4f
                    val name = PdfStyle.layout(checkpoint.name, namePaint, textWidth, Layout.Alignment.ALIGN_CENTER, maxLines = 2)
                    PdfStyle.drawLayout(c, name, x + 8f, textTop)
                    textTop += name.height + 2f
                    val code = PdfStyle.layout(checkpoint.code, codePaint, textWidth, Layout.Alignment.ALIGN_CENTER, maxLines = 1)
                    PdfStyle.drawLayout(c, code, x + 8f, textTop)
                    textTop += code.height + 1f
                    val detail = listOf(checkpoint.type.label, checkpoint.area).filter { it.isNotBlank() }.joinToString("  ·  ")
                    PdfStyle.drawLayout(c, PdfStyle.layout(detail, areaPaint, textWidth, Layout.Alignment.ALIGN_CENTER, maxLines = 1), x + 8f, textTop)
                }
                document.finishPage(page)
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { document.writeTo(it) }
            return pageNumber
        } finally {
            document.close()
        }
    }
}
