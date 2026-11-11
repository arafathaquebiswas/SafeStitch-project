package com.safestitch.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.util.ImageUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Exports a camera-captured [Inspection] entity—including its embedded full-resolution image,
 * formatted capture timestamp, file metadata, and status tags—into a branded A4 PDF file
 * using Apache PDFBox (`pdfbox-android`).
 */
class CapturedInspectionPdfExporter(
    private val appVersion: String = "1.0.0",
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH)

    fun write(
        inspection: Inspection,
        outputFile: File,
        context: Context? = null,
    ): File {
        if (context != null && !PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
        outputFile.parentFile?.mkdirs()

        val file = File(inspection.imagePath)
        val existsOnDisk = file.isFile && file.length() > 0L
        var origWidth = 1920
        var origHeight = 1080
        val bitmapToEmbed: Bitmap = if (existsOnDisk) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val decoded = ImageUtils.decodeSampled(file, MAX_EMBED_DIMENSION_PX)
            if (decoded != null) {
                origWidth = bounds.outWidth.takeIf { it > 0 } ?: decoded.width
                origHeight = bounds.outHeight.takeIf { it > 0 } ?: decoded.height
                decoded
            } else {
                createSampleInspectionBitmap(inspection)
            }
        } else {
            createSampleInspectionBitmap(inspection)
        }

        val tags = inspection.normalizedTags.ifEmpty { listOf(Inspection.TAG_NEEDS_REVIEW) }
        val formattedDate = formatTimestamp(inspection.timestamp)
        val exportedDate = formatTimestamp(System.currentTimeMillis())
        val sizeDescription = if (existsOnDisk) {
            String.format(Locale.US, "%.1f KB (%d bytes)", file.length() / 1024.0, file.length())
        } else {
            "Seeded sample inspection photo (embedded)"
        }

        try {
            PDDocument().use { document ->
                document.documentInformation = PDDocumentInformation().apply {
                    title = safePdfText("SafeStitch Inspection Details #C${inspection.id}")
                    author = "SafeStitch Safety & Equipment Inspection"
                    subject = safePdfText("Captured Inspection #C${inspection.id} | Tags: ${tags.joinToString(", ")}")
                    keywords = safePdfText(tags.joinToString(", "))
                    creator = safePdfText("SafeStitch v$appVersion (PDFBox-Android)")
                    creationDate = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
                }

                val page = PDPage(PDRectangle.A4)
                document.addPage(page)

                val pageWidth = page.mediaBox.width // 595.27563f
                val pageHeight = page.mediaBox.height // 841.8898f
                val margin = 36f
                val contentWidth = pageWidth - margin * 2f

                val pdImage = JPEGFactory.createFromImage(document, bitmapToEmbed, 0.90f)

                PDPageContentStream(document, page).use { cs ->
                    // 1. Branded header banner
                    val bannerHeight = 62f
                    val bannerBottom = pageHeight - margin - bannerHeight
                    cs.setNonStrokingColor(11, 110, 105) // SafeStitch Brand Teal
                    cs.addRect(margin, bannerBottom, contentWidth, bannerHeight)
                    cs.fill()

                    cs.beginText()
                    cs.setNonStrokingColor(255, 255, 255)
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 15f)
                    cs.newLineAtOffset(margin + 14f, bannerBottom + 37f)
                    cs.showText("SAFESTITCH  |  CAPTURED INSPECTION REPORT")
                    cs.endText()

                    cs.beginText()
                    cs.setNonStrokingColor(204, 232, 228)
                    cs.setFont(PDType1Font.HELVETICA, 9.5f)
                    cs.newLineAtOffset(margin + 14f, bannerBottom + 16f)
                    cs.showText(
                        safePdfText(
                            "Record #C${inspection.id}   |   Captured: $formattedDate   |   SafeStitch v$appVersion",
                        ),
                    )
                    cs.endText()

                    // 2. Status Tags Bar
                    var cursorY = bannerBottom - 26f
                    cs.beginText()
                    cs.setNonStrokingColor(11, 110, 105)
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 10f)
                    cs.newLineAtOffset(margin, cursorY)
                    cs.showText("STATUS TAGS:")
                    cs.endText()

                    var badgeX = margin + 88f
                    tags.forEach { tag ->
                        val label = safePdfText(tag.replace('_', ' '))
                        val textWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(label) / 1000f * 8.5f
                        val badgeW = textWidth + 16f
                        val badgeH = 17f
                        val rgb = badgeColorRgb(tag)
                        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
                        cs.addRect(badgeX, cursorY - 4f, badgeW, badgeH)
                        cs.fill()

                        cs.beginText()
                        cs.setNonStrokingColor(255, 255, 255)
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 8.5f)
                        cs.newLineAtOffset(badgeX + 8f, cursorY + 1.5f)
                        cs.showText(label)
                        cs.endText()

                        badgeX += badgeW + 6f
                    }

                    // 3. Metadata Section
                    cursorY -= 24f
                    cs.beginText()
                    cs.setNonStrokingColor(11, 110, 105)
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                    cs.newLineAtOffset(margin, cursorY)
                    cs.showText("CAPTURE & FILE METADATA")
                    cs.endText()

                    cursorY -= 8f
                    val rows = listOf(
                        "Inspection Record ID" to "C-${inspection.id} (Room table: captured_inspections)",
                        "Captured Timestamp" to "$formattedDate (epoch ${inspection.timestamp} ms)",
                        "Status Tags" to tags.joinToString(", ") { it.replace('_', ' ') },
                        "Image File Name" to file.name.ifBlank { inspection.imagePath },
                        "Local Storage Path" to ellipsizeMiddle(inspection.imagePath, 76),
                        "Image Resolution" to "$origWidth x $origHeight px",
                        "File Size" to sizeDescription,
                    )

                    val rowHeight = 20f
                    rows.forEachIndexed { idx, (label, value) ->
                        val rowBottom = cursorY - (idx + 1) * rowHeight
                        if (idx % 2 == 0) {
                            cs.setNonStrokingColor(242, 247, 246)
                        } else {
                            cs.setNonStrokingColor(255, 255, 255)
                        }
                        cs.addRect(margin, rowBottom, contentWidth, rowHeight)
                        cs.fill()

                        cs.setStrokingColor(214, 222, 220)
                        cs.setLineWidth(0.5f)
                        cs.addRect(margin, rowBottom, contentWidth, rowHeight)
                        cs.stroke()

                        cs.beginText()
                        cs.setNonStrokingColor(74, 99, 96)
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 9f)
                        cs.newLineAtOffset(margin + 10f, rowBottom + 6f)
                        cs.showText(safePdfText(label))
                        cs.endText()

                        cs.beginText()
                        cs.setNonStrokingColor(23, 29, 28)
                        cs.setFont(PDType1Font.HELVETICA, 9f)
                        cs.newLineAtOffset(margin + 145f, rowBottom + 6f)
                        cs.showText(safePdfText(value))
                        cs.endText()
                    }

                    cursorY -= rows.size * rowHeight + 22f

                    // 4. Embedded Full-Resolution Image Section
                    cs.beginText()
                    cs.setNonStrokingColor(11, 110, 105)
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                    cs.newLineAtOffset(margin, cursorY)
                    cs.showText("CAPTURED INSPECTION IMAGE")
                    cs.endText()

                    val footerTop = margin + 26f
                    val imageBoxTop = cursorY - 8f
                    val imageBoxBottom = footerTop + 12f
                    val imageBoxHeight = (imageBoxTop - imageBoxBottom).coerceAtLeast(160f)

                    // Outer dark frame for the inspection photo
                    cs.setNonStrokingColor(15, 23, 22)
                    cs.addRect(margin, imageBoxBottom, contentWidth, imageBoxHeight)
                    cs.fill()

                    val pad = 12f
                    val availW = contentWidth - pad * 2f
                    val availH = imageBoxHeight - pad * 2f
                    val imgW = pdImage.width.toFloat().coerceAtLeast(1f)
                    val imgH = pdImage.height.toFloat().coerceAtLeast(1f)
                    val scale = minOf(availW / imgW, availH / imgH)
                    val drawW = imgW * scale
                    val drawH = imgH * scale
                    val drawX = margin + (contentWidth - drawW) / 2f
                    val drawY = imageBoxBottom + (imageBoxHeight - drawH) / 2f

                    cs.drawImage(pdImage, drawX, drawY, drawW, drawH)

                    // 5. Footer
                    cs.setStrokingColor(214, 222, 220)
                    cs.setLineWidth(0.75f)
                    cs.moveTo(margin, footerTop)
                    cs.lineTo(pageWidth - margin, footerTop)
                    cs.stroke()

                    cs.beginText()
                    cs.setNonStrokingColor(90, 98, 96)
                    cs.setFont(PDType1Font.HELVETICA, 8f)
                    cs.newLineAtOffset(margin, margin + 8f)
                    cs.showText(
                        safePdfText(
                            "SafeStitch v$appVersion  |  Generated offline with PDFBox on $exportedDate  |  Record #C${inspection.id}  |  Page 1 of 1",
                        ),
                    )
                    cs.endText()
                }

                document.save(outputFile)
            }
        } finally {
            bitmapToEmbed.recycle()
        }

        return outputFile
    }

    private fun formatTimestamp(millis: Long): String =
        dateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun badgeColorRgb(tag: String): IntArray = when (tag.trim().uppercase(Locale.ROOT)) {
        Inspection.TAG_PASS -> intArrayOf(27, 127, 59)
        Inspection.TAG_FAIL -> intArrayOf(179, 38, 30)
        Inspection.TAG_NEEDS_REVIEW, Inspection.TAG_FOLLOW_UP -> intArrayOf(138, 81, 0)
        else -> intArrayOf(11, 110, 105)
    }

    private fun ellipsizeMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val keep = (maxChars - 3) / 2
        return text.take(keep) + "..." + text.takeLast(keep)
    }

    private fun safePdfText(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when {
                ch == '\u00B7' || ch == '\u2022' -> append('|')
                ch == '\u00D7' -> append('x')
                ch == '\u2013' || ch == '\u2014' -> append('-')
                ch.code in 32..126 -> append(ch)
                ch.code in 160..255 -> append(ch)
                else -> append(' ')
            }
        }
    }

    private fun createSampleInspectionBitmap(inspection: Inspection): Bitmap {
        val width = 1280
        val height = 800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(16, 32, 30))

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, 255, 255, 255)
            strokeWidth = 2f
        }
        for (i in 1..7) {
            val x = width * i / 8f
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        }
        for (j in 1..4) {
            val y = height * j / 5f
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        val primaryTag = inspection.normalizedTags.firstOrNull() ?: Inspection.TAG_NEEDS_REVIEW
        val rgb = badgeColorRgb(primaryTag)
        val accentColor = Color.rgb(rgb[0], rgb[1], rgb[2])

        val frameRect = RectF(160f, 110f, width - 160f, height - 110f)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRoundRect(frameRect, 28f, 28f, framePaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, rgb[0], rgb[1], rgb[2])
            style = Paint.Style.FILL
        }
        canvas.drawCircle(width / 2f, height / 2f - 20f, 130f, circlePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(166, 240, 232)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val fileName = File(inspection.imagePath).name.ifBlank { inspection.imagePath }
        canvas.drawText(fileName, width / 2f, height / 2f - 10f, titlePaint)
        canvas.drawText(
            "Tags: ${inspection.normalizedTags.joinToString(", ")}  |  ${formatTimestamp(inspection.timestamp)}",
            width / 2f,
            height / 2f + 42f,
            subPaint,
        )
        return bitmap
    }

    private companion object {
        const val MAX_EMBED_DIMENSION_PX = 1600
    }
}
