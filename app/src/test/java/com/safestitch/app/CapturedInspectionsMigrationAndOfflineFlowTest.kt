package com.safestitch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.history.CapturedInspectionsViewModel
import com.safestitch.app.util.ImageUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CapturedInspectionsMigrationAndOfflineFlowTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SafeStitchDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        listOf(HELPER_DB, ROOM_DB, SafeStitchDatabase.FILE_NAME).forEach { context.deleteDatabase(it) }
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.string(sql: String): String? = query(sql).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

    private fun createVersion1(name: String) {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO checkpoints (id, code, name, area, type, createdAt) VALUES (1, 'SS-LINE-04', 'Sewing line 4', 'Floor 2', 'SEWING_LINE', 1000)")
            execSQL("INSERT INTO checkpoints (id, code, name, area, type, createdAt) VALUES (2, 'FE-12', 'Fire point 12', '', 'FIRE_SAFETY', 1000)")
            execSQL(
                "INSERT INTO inspections (id, checkpointId, inspectorName, siteName, startedAt, completedAt, generalNotes, reportFileName) " +
                    "VALUES (1, 1, 'R. Ahmed', 'Unit 3', 2000, 3000, 'All lines running', 'report1.pdf')",
            )
            execSQL(
                "INSERT INTO inspections (id, checkpointId, inspectorName, siteName, startedAt, completedAt, generalNotes, reportFileName) " +
                    "VALUES (2, 2, 'R. Ahmed', '', 4000, NULL, '', NULL)",
            )
            execSQL("INSERT INTO checklist_items (id, inspectionId, position, section, question, critical, result, note) VALUES (1, 1, 0, 'Machine safety', 'Guards fitted', 1, 'FAIL', 'Guard missing on M12')")
            execSQL("INSERT INTO checklist_items (id, inspectionId, position, section, question, critical, result, note) VALUES (2, 1, 1, 'Machine safety', 'Belts covered', 0, 'PASS', '')")
            execSQL("INSERT INTO checklist_items (id, inspectionId, position, section, question, critical, result, note) VALUES (3, 2, 0, 'Fire', 'Charged', 0, 'FAIL', 'Draft finding')")
            execSQL("INSERT INTO photos (id, inspectionId, itemId, fileName, takenAt) VALUES (1, 1, 1, 'IMG_evidence_1.jpg', 2500)")
            execSQL("INSERT INTO photos (id, inspectionId, itemId, fileName, takenAt) VALUES (2, 1, NULL, 'IMG_general_1.jpg', 2600)")
            close()
        }
    }

    @Test
    fun version1DataSurvivesMigrationToVersion2WithInspectionsPhotosActionsAndCapturedTable() {
        createVersion1(HELPER_DB)
        val db = helper.runMigrationsAndValidate(HELPER_DB, 2, true)

        assertEquals(2L, db.long("SELECT COUNT(*) FROM checkpoints"))
        assertEquals(2L, db.long("SELECT COUNT(*) FROM inspections"))
        assertEquals(3L, db.long("SELECT COUNT(*) FROM checklist_items"))
        assertEquals(2L, db.long("SELECT COUNT(*) FROM photos"))
        assertEquals("IMG_evidence_1.jpg", db.string("SELECT fileName FROM photos WHERE id = 1"))
        assertEquals(1L, db.long("SELECT itemId FROM photos WHERE id = 1"))
        assertEquals("report1.pdf", db.string("SELECT reportFileName FROM inspections WHERE id = 1"))
        assertEquals("All lines running", db.string("SELECT generalNotes FROM inspections WHERE id = 1"))
        assertEquals("Guard missing on M12", db.string("SELECT note FROM checklist_items WHERE id = 1"))
        assertEquals(3000L, db.long("SELECT updatedAt FROM inspections WHERE id = 1"))

        assertEquals("SS-LINE-04", db.string("SELECT equipmentCode FROM inspections WHERE id = 1"))
        assertEquals("Sewing line 4", db.string("SELECT equipmentName FROM inspections WHERE id = 1"))
        assertEquals("SEWING_LINE", db.string("SELECT equipmentType FROM inspections WHERE id = 1"))

        assertEquals("My factory", db.string("SELECT name FROM factories"))
        val expectedQuestions = CheckpointType.entries.sumOf { ChecklistTemplates.forType(it).size }.toLong()
        assertEquals(expectedQuestions, db.long("SELECT COUNT(*) FROM template_questions"))

        assertEquals("CRITICAL", db.string("SELECT priority FROM checklist_items WHERE id = 1"))
        assertEquals(3000L + FindingDefaults.DAY_MILLIS, db.long("SELECT dueAt FROM checklist_items WHERE id = 1"))
        assertEquals("R. Ahmed", db.string("SELECT responsibleName FROM checklist_items WHERE id = 1"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM corrective_actions"))
        assertEquals("OPEN", db.string("SELECT status FROM corrective_actions"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM action_events"))
        assertEquals(3L, db.long("SELECT COUNT(*) FROM captured_inspections"))
        db.close()
    }

    @Test
    fun upgradedFileOpensThroughSafeStitchDatabaseWithExistingDataAndCapturedInspectionsIntact() = runBlocking {
        createVersion1(ROOM_DB)
        val roomDb = Room.databaseBuilder(context, SafeStitchDatabase::class.java, ROOM_DB).build()
        try {
            val details = roomDb.inspectionDao().get(1)
            assertNotNull(details)
            details!!
            assertEquals("Sewing line 4", details.equipmentName)
            assertEquals(2, details.photos.size)
            assertEquals(listOf("IMG_evidence_1.jpg", "IMG_general_1.jpg"), details.sortedPhotos.map { it.fileName })
            assertEquals(1, details.actions.size)
            val row = roomDb.correctiveActionDao().observeRows().first().single()
            assertEquals(ActionStatus.OPEN, row.action.status)
            assertEquals("Guard missing on M12", row.action.description)
            assertEquals(null, roomDb.inspectionDao().get(2)!!.inspection.completedAt)
            val captured = roomDb.inspectionDao().observeCapturedInspections().first()
            assertEquals(3, captured.size)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun offlineFreshPhotoCaptureAppearsInCapturedInspectionsChangesTagAndSurvivesAppRestart() = runBlocking {
        val photoStorage = PhotoStorage(context)
        val freshPhotoFile = photoStorage.newPhotoFile()
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(15, 21, 20))
            drawCircle(320f, 200f, 110f, Paint().apply { color = Color.rgb(11, 110, 105) })
        }
        freshPhotoFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bitmap.recycle()
        assertTrue("Freshly captured photo file must exist on disk", freshPhotoFile.isFile && freshPhotoFile.length() > 0L)

        val captureTimestamp = 1_762_800_123_456L
        val expectedFormattedTimestamp = formatDateTime(captureTimestamp)
        var capturedId: Long = -1L

        // Session 1 (offline): open on-disk Room DB, verify 3 sample rows, capture 1 fresh photo, change its tag.
        val db1 = SafeStitchDatabase.create(context)
        try {
            val repo1 = InspectionRepository(db1, photoStorage, clock = { captureTimestamp })
            val initialRows = repo1.observeCapturedInspections().first()
            assertEquals("Expected 3 seeded sample rows on initial launch", 3, initialRows.size)

            capturedId = repo1.recordCapturedInspection(
                imagePath = freshPhotoFile.absolutePath,
                statusTags = listOf(Inspection.TAG_NEEDS_REVIEW),
                timestamp = captureTimestamp,
            )
            assertTrue(capturedId > 0L)

            val vm1 = CapturedInspectionsViewModel(repo1)
            val rowsAfterCapture = repo1.observeCapturedInspections().first()
            assertEquals(4, rowsAfterCapture.size)
            val freshRow = rowsAfterCapture.first { it.id == capturedId }
            assertEquals(freshPhotoFile.absolutePath, freshRow.imagePath)
            assertEquals(captureTimestamp, freshRow.timestamp)
            assertEquals(listOf(Inspection.TAG_NEEDS_REVIEW), freshRow.normalizedTags)

            // Change its tag from NEEDS_REVIEW to PASS and add a custom tag on the Details screen
            repo1.toggleCapturedInspectionTag(freshRow, Inspection.TAG_PASS)
            val withPass = repo1.observeCapturedInspection(capturedId).first()!!
            repo1.toggleCapturedInspectionTag(withPass, Inspection.TAG_NEEDS_REVIEW)

            val updatedRow = repo1.observeCapturedInspection(capturedId).first()!!
            assertEquals(listOf(Inspection.TAG_PASS), updatedRow.normalizedTags)

            // Verify custom tag add & remove for InspectionDetailsScreen
            repo1.addCapturedInspectionTag(updatedRow, "line 4 check")
            val withCustom = repo1.observeCapturedInspection(capturedId).first()!!
            assertEquals(listOf(Inspection.TAG_PASS, "LINE_4_CHECK"), withCustom.normalizedTags)

            repo1.removeCapturedInspectionTag(withCustom, "LINE_4_CHECK")
            val afterCustomRemoved = repo1.observeCapturedInspection(capturedId).first()!!
            assertEquals(listOf(Inspection.TAG_PASS), afterCustomRemoved.normalizedTags)
            assertNotNull(vm1)
        } finally {
            db1.close()
        }

        // Session 2 (app restart): reopen on-disk Room DB and confirm thumbnail, timestamp, and changed tag survive.
        val db2 = SafeStitchDatabase.create(context)
        try {
            val repo2 = InspectionRepository(db2, photoStorage)
            val persistedRows = repo2.observeCapturedInspections().first()
            assertEquals(4, persistedRows.size)

            val survived = repo2.observeCapturedInspection(capturedId).first()!!
            assertEquals(freshPhotoFile.absolutePath, survived.imagePath)
            assertEquals(captureTimestamp, survived.timestamp)
            assertEquals(expectedFormattedTimestamp, formatDateTime(survived.timestamp))
            assertEquals(listOf(Inspection.TAG_PASS), survived.normalizedTags)

            val decodedThumb = ImageUtils.decodeSampled(File(survived.imagePath), 2048)
            assertNotNull("Full-resolution image must decode from persisted imagePath after restart", decodedThumb)

            // Verify PDFBox branded PDF export for the captured inspection record
            val reportService = com.safestitch.app.pdf.ReportService(context, repo2, photoStorage)
            val exportedPdf = reportService.exportCapturedInspectionPdf(survived)
            assertTrue("Exported branded PDF must exist and be non-empty", exportedPdf.isFile && exportedPdf.length() > 500L)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(exportedPdf).use { doc ->
                assertEquals(1, doc.numberOfPages)
                assertEquals("SafeStitch Inspection Details #C$capturedId", doc.documentInformation.title)
                assertTrue(doc.documentInformation.subject.orEmpty().contains("PASS"))
            }
            exportedPdf.delete()
            Unit
        } finally {
            db2.close()
            freshPhotoFile.delete()
        }
    }

    private companion object {
        const val HELPER_DB = "disposable-migration-test.db"
        const val ROOM_DB = "disposable-upgrade-test.db"
    }
}
