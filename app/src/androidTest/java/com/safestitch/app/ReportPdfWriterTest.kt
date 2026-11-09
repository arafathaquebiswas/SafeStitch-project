package com.safestitch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.RGBLuminanceSource
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.pdf.QrLabelPdfWriter
import com.safestitch.app.pdf.ReportPdfWriter
import com.safestitch.app.qr.QrCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReportPdfWriterTest {
    private lateinit var context: Context
    private lateinit var database: SafeStitchDatabase
    private lateinit var photos: PhotoStorage
    private lateinit var repository: InspectionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = SafeStitchDatabase.inMemory(context)
        photos = PhotoStorage(context)
        repository = InspectionRepository(database, photos)
    }

    @After
    fun tearDown() = database.close()

    private fun fakePhoto(): String {
        val file = photos.newPhotoFile()
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(200, 220, 215))
            drawCircle(800f, 600f, 300f, Paint().apply { color = Color.rgb(179, 38, 30) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return file.name
    }

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } }

    @Test
    fun writesMultiPageReportWithPhotos() {
        runBlocking {
            val checkpointId = repository.createCheckpoint("SS-LINE-04", "Sewing line 4", "Floor 2", CheckpointType.SEWING_LINE)
            val inspectionId = repository.startOrResumeInspection(checkpointId, "R. Ahmed", "Unit 3")
            val items = repository.getInspection(inspectionId)!!.sortedItems
            items.forEach { repository.setItemResult(it.id, ItemResult.PASS) }
            repository.setItemResult(items[1].id, ItemResult.FAIL)
            repository.setItemNote(items[1].id, "Belt cover missing on machine 12. Maintenance fitted a new cover at 10:40.")
            repository.setGeneralNotes(inspectionId, "Line running at full capacity during inspection.")
            repository.addPhoto(inspectionId, items[1].id, fakePhoto())
            repeat(3) { repository.addPhoto(inspectionId, null, fakePhoto()) }
            repository.markCompleted(inspectionId, System.currentTimeMillis(), "unused.pdf")

            val details = repository.getInspection(inspectionId)!!
            val out = File(context.cacheDir, "report-test.pdf")
            val pages = ReportPdfWriter(photos, "test").write(details, out)

            assertTrue("expected at least 2 pages, got $pages", pages >= 2)
            val header = ByteArray(4).also { bytes -> out.inputStream().use { it.read(bytes) } }
            assertEquals("%PDF", String(header))
            assertEquals(pages, pageCount(out))
        }
    }

    @Test
    fun printedQrLabelDecodesBackToTheCheckpointCode() {
        runBlocking {
            val id = repository.createCheckpoint("SS-FIRE-12", "Fire extinguisher 12", "Stairwell B", CheckpointType.FIRE_SAFETY)
            val checkpoint = repository.getCheckpoint(id)!!
            val out = File(context.cacheDir, "labels-test.pdf")
            assertEquals(1, QrLabelPdfWriter().write(listOf(checkpoint), out, "test"))

            val bitmap = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                    }
                }
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QrCodec.decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
            assertEquals(QrPayload.forCheckpoint("SS-FIRE-12"), decoded)
        }
    }
}
