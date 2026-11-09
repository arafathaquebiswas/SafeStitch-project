package com.safestitch.app

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.FindingDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real version-1 database FILES on the device (disposable names, never the app's safestitch.db),
 * migrated to version 2: no inspection or evidence photo may be lost.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SafeStitchDatabase::class.java)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        listOf(HELPER_DB, ROOM_DB).forEach { context.deleteDatabase(it) }
    }

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.string(sql: String): String? = query(sql).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

    /** The same data a phone running version 1.0.0 (database v1) would have. */
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
    fun version1DataSurvivesAndGetsHierarchySnapshotsQuestionsAndActions() {
        createVersion1(HELPER_DB)
        val db = helper.runMigrationsAndValidate(HELPER_DB, 2, true)

        // Existing rows are all still there, unchanged where it matters.
        assertEquals(2, db.long("SELECT COUNT(*) FROM checkpoints"))
        assertEquals(2, db.long("SELECT COUNT(*) FROM inspections"))
        assertEquals(3, db.long("SELECT COUNT(*) FROM checklist_items"))
        assertEquals(2, db.long("SELECT COUNT(*) FROM photos"))
        assertEquals("IMG_evidence_1.jpg", db.string("SELECT fileName FROM photos WHERE id = 1"))
        assertEquals(1, db.long("SELECT itemId FROM photos WHERE id = 1"))
        assertEquals("report1.pdf", db.string("SELECT reportFileName FROM inspections WHERE id = 1"))
        assertEquals("All lines running", db.string("SELECT generalNotes FROM inspections WHERE id = 1"))
        assertEquals("Guard missing on M12", db.string("SELECT note FROM checklist_items WHERE id = 1"))
        assertEquals(3000, db.long("SELECT updatedAt FROM inspections WHERE id = 1"))

        // Equipment snapshot and legacy types are kept exactly.
        assertEquals("SS-LINE-04", db.string("SELECT equipmentCode FROM inspections WHERE id = 1"))
        assertEquals("Sewing line 4", db.string("SELECT equipmentName FROM inspections WHERE id = 1"))
        assertEquals("SEWING_LINE", db.string("SELECT equipmentType FROM inspections WHERE id = 1"))
        assertEquals("FIRE_SAFETY", db.string("SELECT type FROM checkpoints WHERE id = 2"))

        // Free-text areas became a hierarchy and the equipment points at it.
        assertEquals("My factory", db.string("SELECT name FROM factories"))
        assertEquals("Floor 2", db.string("SELECT a.name FROM checkpoints c JOIN areas a ON a.id = c.areaId WHERE c.id = 1"))
        assertEquals("General area", db.string("SELECT a.name FROM checkpoints c JOIN areas a ON a.id = c.areaId WHERE c.id = 2"))

        // Editable questions were seeded for every type (five current + three earlier).
        val expectedQuestions = CheckpointType.entries.sumOf { ChecklistTemplates.forType(it).size }.toLong()
        assertEquals(expectedQuestions, db.long("SELECT COUNT(*) FROM template_questions"))

        // Failed items got priority, due date and a responsible person; only the SUBMITTED one got an action + event.
        assertEquals("CRITICAL", db.string("SELECT priority FROM checklist_items WHERE id = 1"))
        assertEquals(3000 + FindingDefaults.DAY_MILLIS, db.long("SELECT dueAt FROM checklist_items WHERE id = 1"))
        assertEquals("R. Ahmed", db.string("SELECT responsibleName FROM checklist_items WHERE id = 1"))
        assertEquals("MEDIUM", db.string("SELECT priority FROM checklist_items WHERE id = 3"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM corrective_actions"))
        assertEquals("OPEN", db.string("SELECT status FROM corrective_actions"))
        assertEquals("R. Ahmed", db.string("SELECT responsibleName FROM corrective_actions"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM action_events"))
        assertEquals("OPEN", db.string("SELECT toStatus FROM action_events"))
        db.close()
    }

    @Test
    fun upgradedFileOpensThroughTheAppsRoomDatabaseWithInspectionAndEvidenceIntact() = runBlocking {
        createVersion1(ROOM_DB)
        helper.closeWhenFinished(Room.databaseBuilder(context, SafeStitchDatabase::class.java, ROOM_DB).build().also { roomDb ->
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
            assertEquals(1, roomDb.correctiveActionDao().observeEvents(row.action.id).first().size)
            // The draft keeps its unanswered/failed items and stays a draft.
            assertEquals(null, roomDb.inspectionDao().get(2)!!.inspection.completedAt)
        })
    }

    private companion object {
        const val HELPER_DB = "disposable-migration-test.db"
        const val ROOM_DB = "disposable-upgrade-test.db"
    }
}
