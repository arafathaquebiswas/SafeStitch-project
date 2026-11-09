package com.safestitch.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.safestitch.app.BuildConfig
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.domain.ReportFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PdfInfo(val pageCount: Int, val sizeBytes: Long)

/** Creates, inspects and previews the PDFs stored in the app's private files/reports and files/labels folders. */
class ReportService(
    context: Context,
    private val repository: InspectionRepository,
    private val photoStorage: PhotoStorage,
) {
    private val reportsDir = File(context.filesDir, "reports")
    private val labelsDir = File(context.filesDir, "labels")

    /** Generates the report PDF. [completedAt] is used for drafts being completed right now. */
    suspend fun generateReport(inspectionId: Long, completedAt: Long?): File = withContext(Dispatchers.IO) {
        val details = repository.getInspection(inspectionId)
            ?: throw IllegalStateException("Inspection $inspectionId no longer exists.")
        val finishedAt = completedAt ?: details.inspection.completedAt ?: System.currentTimeMillis()
        val effective = details.copy(inspection = details.inspection.copy(completedAt = finishedAt))
        reportsDir.mkdirs()
        val name = ReportFileNames.report(details.equipmentCode, inspectionId, finishedAt)
        val target = File(reportsDir, name)
        val temp = File(reportsDir, "$name.part")
        try {
            ReportPdfWriter(photoStorage, BuildConfig.VERSION_NAME).write(effective, temp)
            if (target.exists() && !target.delete()) throw IOException("Could not replace $name")
            if (!temp.renameTo(target)) throw IOException("Could not save $name")
        } finally {
            temp.delete()
        }
        details.inspection.reportFileName?.takeIf { it != name }?.let { File(reportsDir, it).delete() }
        target
    }

    fun reportFile(fileName: String): File = File(reportsDir, fileName)

    fun deleteReport(fileName: String) {
        File(reportsDir, fileName).delete()
    }

    suspend fun generateLabels(checkpoints: List<CheckpointEntity>): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        labelsDir.mkdirs()
        labelsDir.listFiles()?.forEach { it.delete() } // keep only the latest sheet
        val file = File(labelsDir, ReportFileNames.labels(now))
        val stamp = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        QrLabelPdfWriter().write(checkpoints, file, stamp)
        file
    }

    suspend fun inspect(file: File): PdfInfo = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> PdfInfo(renderer.pageCount, file.length()) }
        }
    }

    /** Renders one page to a bitmap for the in-app preview. */
    suspend fun renderPage(file: File, pageIndex: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val heightPx = (widthPx.toFloat() * page.height / page.width).toInt()
                    createBitmap(widthPx, heightPx).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}
