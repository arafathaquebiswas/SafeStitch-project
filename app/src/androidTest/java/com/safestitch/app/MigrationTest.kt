package com.safestitch.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.FindingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real version-1 database on the device, migrated to version 2: nothing may be lost. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SafeStitchDatabase::class.java)

    private val name = "migration-test.db"

    private fun SupportSQLiteDatabase.long(sql: String): Long = query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun SupportSQLiteDatabase.string(sql: String): String? = query(sql).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

    @Test
    fun version1DataSurvivesAndGetsHierarchyQuestionsAndActions() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO checkpoints (id, code, name, area, type, createdAt) VALUES (1, 'SS-LINE-04', 'Sewing line 4', 'Floor 2', 'SEWING_LINE', 1000)")
            execSQL("INSERT INTO checkpoints (id, code, name, area, type, createdAt) VALUES (2, 'FE-12', 'Extinguisher 12', '', 'FIRE_SAFETY', 1000)")
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
            execSQL("INSERT INTO photos (id, inspectionId, itemId, fileName, takenAt) VALUES (1, 1, 1, 'IMG_1.jpg', 2500)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 2, true)

        // Existing rows are all still there, unchanged where it matters.
        assertEquals(2, db.long("SELECT COUNT(*) FROM checkpoints"))
        assertEquals(2, db.long("SELECT COUNT(*) FROM inspections"))
        assertEquals(3, db.long("SELECT COUNT(*) FROM checklist_items"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM photos"))
        assertEquals("report1.pdf", db.string("SELECT reportFileName FROM inspections WHERE id = 1"))
        assertEquals("All lines running", db.string("SELECT generalNotes FROM inspections WHERE id = 1"))
        assertEquals("Guard missing on M12", db.string("SELECT note FROM checklist_items WHERE id = 1"))
        assertEquals(3000, db.long("SELECT updatedAt FROM inspections WHERE id = 1"))

        // Free-text areas became a hierarchy and the equipment points at it.
        assertEquals("My factory", db.string("SELECT name FROM factories"))
        assertEquals(2, db.long("SELECT COUNT(*) FROM areas"))
        assertEquals("Floor 2", db.string("SELECT a.name FROM checkpoints c JOIN areas a ON a.id = c.areaId WHERE c.id = 1"))
        assertEquals("General area", db.string("SELECT a.name FROM checkpoints c JOIN areas a ON a.id = c.areaId WHERE c.id = 2"))
        assertNotNull(db.string("SELECT areaId FROM checkpoints WHERE id = 2"))

        // Editable questions were seeded from the built-in checklists.
        val expectedQuestions = CheckpointType.entries.sumOf { ChecklistTemplates.forType(it).size }.toLong()
        assertEquals(expectedQuestions, db.long("SELECT COUNT(*) FROM template_questions"))

        // Failed items got a priority and due date; only the SUBMITTED inspection got a corrective action.
        assertEquals("CRITICAL", db.string("SELECT priority FROM checklist_items WHERE id = 1"))
        assertEquals(3000 + FindingDefaults.DAY_MILLIS, db.long("SELECT dueAt FROM checklist_items WHERE id = 1"))
        assertEquals("MEDIUM", db.string("SELECT priority FROM checklist_items WHERE id = 3"))
        assertEquals(1, db.long("SELECT COUNT(*) FROM corrective_actions"))
        assertEquals(1, db.long("SELECT itemId FROM corrective_actions"))
        assertEquals("OPEN", db.string("SELECT status FROM corrective_actions"))
        assertEquals("Guard missing on M12", db.string("SELECT description FROM corrective_actions"))
        db.close()
    }
}
