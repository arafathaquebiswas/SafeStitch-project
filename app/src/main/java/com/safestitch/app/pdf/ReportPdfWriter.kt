package com.safestitch.app.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Outcome
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.util.ImageUtils
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Renders a complete inspection report as an A4 PDF using Android's built-in PdfDocument (fully offline). */
class ReportPdfWriter(
    private val photoStorage: PhotoStorage,
    private val appVersion: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** Writes the report to [out] and returns the number of pages. */
    fun write(details: InspectionWithDetails, out: File): Int {
        val document = PdfDocument()
        try {
            val inspection = details.inspection
            val generatedAt = format(clock())
            val pager = PdfPager(document) { page ->
                "SafeStitch $appVersion  ·  Report R${inspection.id}  ·  ${details.checkpoint.code}  ·  " +
                    "Generated offline $generatedAt  ·  Page $page"
            }
            pager.newPage()
            drawTitle(pager, details)
            drawInfo(pager, details)
            drawResult(pager, details.summary())
            drawChecklist(pager, details)
            if (inspection.generalNotes.isNotBlank()) {
                pager.heading("General notes", keepWithNext = 30f)
                pager.flowText(inspection.generalNotes.trim(), PdfStyle.text(9.5f))
            }
            drawCorrectiveActions(pager, details)
            drawPhotos(pager, details)
            drawSignOff(pager, details)
            pager.finish()
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { document.writeTo(it) }
            return pager.pageNumber
        } finally {
            document.close()
        }
    }

    private fun drawTitle(pager: PdfPager, details: InspectionWithDetails) {
        val c = pager.canvas
        val top = pager.y
        c.drawRoundRect(RectF(pager.left, top, pager.right, top + 54f), 6f, 6f, PdfStyle.fill(PdfStyle.BRAND))
        c.drawText("SafeStitch", pager.left + 14f, top + 25f, PdfStyle.text(20f, Color.WHITE, bold = true))
        c.drawText("Inspection report", pager.left + 14f, top + 43f, PdfStyle.text(11f, Color.WHITE))
        val right = PdfStyle.text(11f, Color.WHITE, bold = true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("Report R${details.inspection.id}", pager.right - 14f, top + 25f, right)
        val rightSmall = PdfStyle.text(9f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(details.checkpoint.type.label, pager.right - 14f, top + 43f, rightSmall)
        pager.y = top + 54f + 14f
    }

    private fun drawInfo(pager: PdfPager, details: InspectionWithDetails) {
        val inspection = details.inspection
        val checkpoint = details.checkpoint
        val rows = listOf(
            "Checkpoint" to "${checkpoint.name} (${checkpoint.code})",
            "Type" to checkpoint.type.label,
            "Location" to inspection.siteName.ifBlank { "—" },
            "Exact spot" to checkpoint.area.ifBlank { "—" },
            "Inspector" to inspection.inspectorName.ifBlank { "—" },
            "Started" to format(inspection.startedAt),
            "Completed" to (inspection.completedAt?.let(::format) ?: "Not completed (draft)"),
        )
        val qrSize = 96f
        val labelWidth = 88f
        val valueWidth = pager.width - qrSize - 16f - labelWidth
        val top = pager.y
        var y = top
        val c = pager.canvas
        val labelPaint = PdfStyle.text(8.5f, PdfStyle.MUTED, bold = true)
        val valuePaint = PdfStyle.text(10f)
        rows.forEach { (label, value) ->
            val layout = PdfStyle.layout(value, valuePaint, valueWidth.toInt())
            c.drawText(label.uppercase(Locale.ENGLISH), pager.left, y + 10f, labelPaint)
            PdfStyle.drawLayout(c, layout, pager.left + labelWidth, y)
            y += max(layout.height.toFloat(), 12f) + 5f
        }
        val qrX = pager.right - qrSize
        PdfStyle.drawQr(c, QrPayload.forCheckpoint(checkpoint.code), qrX, top, qrSize)
        val caption = PdfStyle.text(7f, PdfStyle.MUTED).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Checkpoint QR", qrX + qrSize / 2, top + qrSize + 10f, caption)
        pager.y = max(y, top + qrSize + 14f) + 8f
    }

    private fun drawResult(pager: PdfPager, summary: InspectionSummary) {
        val color = when (summary.outcome) {
            Outcome.PASS -> PdfStyle.PASS
            Outcome.FAIL -> PdfStyle.FAIL
            Outcome.IN_PROGRESS -> PdfStyle.NEUTRAL
        }
        val height = if (summary.criticalFailures > 0) 72f else 60f
        pager.ensure(height + 12f)
        val c = pager.canvas
        val rect = RectF(pager.left, pager.y, pager.right, pager.y + height)
        c.drawRoundRect(rect, 6f, 6f, PdfStyle.fill(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color))))
        c.drawRoundRect(rect, 6f, 6f, PdfStyle.stroke(color, 1.2f))
        c.drawText("RESULT: ${summary.outcome.label}", rect.left + 14f, rect.top + 26f, PdfStyle.text(17f, color, bold = true))
        val stats = "Score ${summary.scorePercent}% (pass mark ${InspectionSummary.PASS_MARK_PERCENT}%)  ·  " +
            "${summary.passed} passed  ·  ${summary.failed} failed  ·  ${summary.notApplicable} N/A  ·  " +
            "${summary.criticalFailures} critical failure${if (summary.criticalFailures == 1) "" else "s"}"
        c.drawText(stats, rect.left + 14f, rect.top + 45f, PdfStyle.text(9.5f))
        if (summary.criticalFailures > 0) {
            c.drawText(
                "A failed critical item fails the checkpoint regardless of the score.",
                rect.left + 14f,
                rect.top + 60f,
                PdfStyle.text(8f, PdfStyle.FAIL, italic = true),
            )
        }
        pager.y = rect.bottom + 10f
    }

    private fun drawChecklist(pager: PdfPager, details: InspectionWithDetails) {
        pager.heading("Checklist", keepWithNext = 60f)
        val numberWidth = 22f
        val resultWidth = 54f
        val questionWidth = pager.width - numberWidth - resultWidth - 10f
        val questionPaint = PdfStyle.text(9.5f)
        val notePaint = PdfStyle.text(8.5f, PdfStyle.MUTED, italic = true)
        val photosPerItem = details.photos.groupBy { it.itemId }

        details.sortedItems.groupBy { it.section }.forEach { (section, items) ->
            drawSectionHeader(pager, section)
            items.forEach { item ->
                val text = SpannableStringBuilder(item.question)
                if (item.critical) {
                    val start = text.length
                    text.append("   CRITICAL")
                    text.setSpan(ForegroundColorSpan(PdfStyle.FAIL), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val layout = PdfStyle.layout(text, questionPaint, questionWidth.toInt())
                val rowHeight = max(layout.height.toFloat(), 14f) + 8f
                if (pager.ensure(rowHeight)) drawSectionHeader(pager, "$section (continued)")
                val c = pager.canvas
                val top = pager.y
                c.drawText("${item.position + 1}.", pager.left + 2f, top + 13f, PdfStyle.text(9.5f, PdfStyle.MUTED, bold = true))
                PdfStyle.drawLayout(c, layout, pager.left + numberWidth, top + 4f)
                val badgeColor = when (item.result) {
                    ItemResult.PASS -> PdfStyle.PASS
                    ItemResult.FAIL -> PdfStyle.FAIL
                    ItemResult.NOT_APPLICABLE, ItemResult.UNANSWERED -> PdfStyle.NEUTRAL
                }
                PdfStyle.badge(c, item.result.shortLabel, badgeColor, RectF(pager.right - resultWidth, top + 3f, pager.right, top + 17f))
                pager.y = top + rowHeight
                val photoCount = photosPerItem[item.id]?.size ?: 0
                val extra = buildString {
                    if (item.note.isNotBlank()) append("Note: ").append(item.note.trim())
                    if (item.result == ItemResult.FAIL && item.priority != null) {
                        if (isNotEmpty()) append("   ")
                        append("Priority ${item.priority.label}")
                        item.dueAt?.let { append(" · due ").append(formatDate(it)) }
                    }
                    if (photoCount > 0) {
                        if (isNotEmpty()) append("   ")
                        append(if (photoCount == 1) "[1 photo]" else "[$photoCount photos]")
                    }
                }
                if (extra.isNotEmpty()) {
                    pager.y -= 3f
                    pager.flowText(extra, notePaint, x = pager.left + numberWidth, width = questionWidth, spacingAfter = 5f)
                }
                pager.canvas.drawLine(pager.left, pager.y, pager.right, pager.y, PdfStyle.stroke(PdfStyle.RULE, 0.5f))
            }
            pager.y += 6f
        }
    }

    private fun drawSectionHeader(pager: PdfPager, title: String) {
        pager.ensure(40f)
        val c = pager.canvas
        c.drawRect(pager.left, pager.y, pager.right, pager.y + 18f, PdfStyle.fill(PdfStyle.ZEBRA))
        c.drawText(title, pager.left + 6f, pager.y + 12.5f, PdfStyle.text(9.5f, PdfStyle.BRAND, bold = true))
        pager.y += 20f
    }

    private fun drawPhotos(pager: PdfPager, details: InspectionWithDetails) {
        val photos = details.sortedPhotos
        if (photos.isEmpty()) return
        val gap = 12f
        val cellWidth = (pager.width - gap) / 2
        val boxHeight = cellWidth * 0.75f
        val captionPaint = PdfStyle.text(8f, PdfStyle.MUTED)
        val itemsById = details.items.associateBy { it.id }
        val rows = photos.chunked(2).mapIndexed { rowIndex, pair ->
            pair to pair.mapIndexed { i, photo ->
                val itemLabel = itemsById[photo.itemId]?.let { "Item ${it.position + 1}: ${it.question}" }
                PdfStyle.layout(caption(rowIndex * 2 + i + 1, photo, itemLabel), captionPaint, cellWidth.toInt(), maxLines = 3)
            }
        }
        val firstRowHeight = boxHeight + rows.first().second.maxOf { it.height } + 16f
        pager.heading("Photos (${photos.size})", keepWithNext = firstRowHeight)
        rows.forEach { (pair, captions) ->
            val captionHeight = captions.maxOf { it.height }.toFloat()
            pager.ensure(boxHeight + captionHeight + 16f)
            val c = pager.canvas
            pair.forEachIndexed { i, photo ->
                val x = pager.left + i * (cellWidth + gap)
                val box = RectF(x, pager.y, x + cellWidth, pager.y + boxHeight)
                c.drawRect(box, PdfStyle.fill(PdfStyle.ZEBRA))
                val bitmap = runCatching { ImageUtils.decodeSampled(photoStorage.file(photo.fileName), MAX_PHOTO_PIXELS) }.getOrNull()
                if (bitmap != null) {
                    val scale = min(box.width() / bitmap.width, box.height() / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val dest = RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
                    c.drawBitmap(bitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
                    bitmap.recycle()
                } else {
                    val missing = PdfStyle.text(9f, PdfStyle.MUTED, italic = true).apply { textAlign = Paint.Align.CENTER }
                    c.drawText("Photo file missing", box.centerX(), box.centerY(), missing)
                }
                c.drawRect(box, PdfStyle.stroke(PdfStyle.RULE))
                PdfStyle.drawLayout(c, captions[i], x, box.bottom + 4f)
            }
            pager.y += boxHeight + captionHeight + 14f
        }
    }

    /**
     * One corrective action per failed item. On submission the actions are not stored yet, so they are
     * derived from the failed items (status Open); a regenerated PDF shows the stored status.
     */
    private fun drawCorrectiveActions(pager: PdfPager, details: InspectionWithDetails) {
        val failed = details.sortedItems.filter { it.result == ItemResult.FAIL }
        if (failed.isEmpty()) return
        val stored = details.actions.associateBy { it.itemId }
        pager.heading("Corrective actions (${failed.size})", keepWithNext = 50f)
        val textPaint = PdfStyle.text(9.5f)
        val small = PdfStyle.text(8.5f, PdfStyle.MUTED)
        failed.forEach { item ->
            val action = stored[item.id]
            val priority = action?.priority ?: item.priority
            val due = action?.dueAt ?: item.dueAt
            val status = action?.status ?: ActionStatus.OPEN
            pager.ensure(46f)
            val c = pager.canvas
            val top = pager.y
            val color = when (status) {
                ActionStatus.OPEN -> PdfStyle.FAIL
                ActionStatus.RESOLVED -> PdfStyle.NEUTRAL
                ActionStatus.VERIFIED -> PdfStyle.PASS
            }
            PdfStyle.badge(c, status.label.uppercase(Locale.ENGLISH), color, RectF(pager.right - 120f, top + 2f, pager.right, top + 16f))
            val title = PdfStyle.layout("${item.position + 1}. ${item.question}", textPaint, (pager.width - 130f).toInt())
            PdfStyle.drawLayout(c, title, pager.left, top + 2f)
            pager.y = top + max(title.height.toFloat(), 16f) + 4f
            val lines = buildString {
                append("Priority ${priority?.label ?: "—"} · due ${due?.let(::formatDate) ?: "—"}")
                append("\nFinding: ").append(action?.description ?: item.note.trim())
                action?.resolutionNote?.let { append("\nFix: ").append(it).append(" (").append(action.resolvedByName.orEmpty()).append(")") }
                action?.verifiedAt?.let { append("\nVerified by ").append(action.verifiedByName.orEmpty()).append(" on ").append(formatDate(it)) }
            }
            pager.flowText(lines, small, spacingAfter = 6f)
            pager.canvas.drawLine(pager.left, pager.y, pager.right, pager.y, PdfStyle.stroke(PdfStyle.RULE, 0.5f))
            pager.y += 6f
        }
    }

    private fun formatDate(millis: Long): String = dayFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun caption(number: Int, photo: PhotoEntity, itemLabel: String?): String =
        "Photo $number  ·  ${format(photo.takenAt)}\n${itemLabel ?: "General photo"}"

    private fun drawSignOff(pager: PdfPager, details: InspectionWithDetails) {
        pager.ensure(96f)
        pager.heading("Sign-off")
        val c = pager.canvas
        val paint = PdfStyle.text(9.5f)
        val line = PdfStyle.stroke(PdfStyle.INK, 0.6f)
        val half = pager.width / 2
        c.drawText("Inspector: ${details.inspection.inspectorName.ifBlank { "—" }}", pager.left, pager.y + 12f, paint)
        pager.y += 40f
        c.drawLine(pager.left, pager.y, pager.left + half - 20f, pager.y, line)
        c.drawLine(pager.left + half, pager.y, pager.right, pager.y, line)
        val small = PdfStyle.text(8f, PdfStyle.MUTED)
        c.drawText("Inspector signature", pager.left, pager.y + 11f, small)
        c.drawText("Supervisor / factory representative", pager.left + half, pager.y + 11f, small)
        pager.y += 20f
    }

    private fun format(millis: Long): String = dateFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    private companion object {
        /** Longest edge of photos embedded in the PDF; keeps reports small enough to email. */
        const val MAX_PHOTO_PIXELS = 900
    }
}
