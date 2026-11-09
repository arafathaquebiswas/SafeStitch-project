package com.safestitch.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.FindingDefaults

/**
 * Version 1 -> 2. Room adds the new tables/columns automatically (AutoMigration); this spec then
 * converts the existing data so nothing is lost:
 *  - every old free-text "area" becomes an Area under "My factory › Main building › Floor not set";
 *  - each inspection gets its equipment snapshot (code, name, type) from the equipment it belongs to;
 *  - the built-in checklists become editable template questions;
 *  - failed items get a priority, due date and responsible person (the inspector), and each failed item
 *    of a submitted inspection gets exactly one corrective action with a "created" history event.
 * Photos (evidence) are not touched: rows and files stay exactly as they were.
 */
class Migration1To2 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        DatabaseSeed.seedQuestions(db)
        DatabaseSeed.migrateLegacyLocations(db)
        DatabaseSeed.backfillSnapshotsAndFindings(db)
    }
}

object DatabaseSeed {
    fun seedQuestions(db: SupportSQLiteDatabase) {
        CheckpointType.entries.forEach { type ->
            ChecklistTemplates.forType(type).forEachIndexed { index, item ->
                db.insert(
                    "template_questions",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("type", type.name)
                        put("position", index)
                        put("section", item.section)
                        put("question", item.question)
                        put("critical", if (item.critical) 1 else 0)
                        put("active", 1)
                    },
                )
            }
        }
    }

    fun migrateLegacyLocations(db: SupportSQLiteDatabase) {
        val legacyAreas = db.query("SELECT DISTINCT TRIM(area) FROM checkpoints").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty()) }
        }
        if (legacyAreas.isEmpty()) return
        val factoryId = insert(db, "factories", ContentValues().apply { put("name", "My factory") })
        val buildingId = insert(db, "buildings", ContentValues().apply { put("factoryId", factoryId); put("name", "Main building") })
        val floorId = insert(db, "floors", ContentValues().apply { put("buildingId", buildingId); put("name", "Floor not set") })
        legacyAreas.forEach { legacy ->
            val areaId = insert(db, "areas", ContentValues().apply { put("floorId", floorId); put("name", legacy.ifBlank { "General area" }) })
            // The old text now lives in the area name, so the "exact spot" starts empty.
            // "areaId IS NULL": equipment already moved (and its text cleared) must not match the blank-area pass.
            db.execSQL("UPDATE checkpoints SET areaId = ?, area = '' WHERE areaId IS NULL AND TRIM(area) = ?", arrayOf<Any>(areaId, legacy))
        }
    }

    fun backfillSnapshotsAndFindings(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE inspections SET updatedAt = COALESCE(completedAt, startedAt), " +
                "equipmentCode = (SELECT c.code FROM checkpoints c WHERE c.id = inspections.checkpointId), " +
                "equipmentName = (SELECT c.name FROM checkpoints c WHERE c.id = inspections.checkpointId), " +
                "equipmentType = (SELECT c.type FROM checkpoints c WHERE c.id = inspections.checkpointId)",
        )
        db.execSQL(
            "UPDATE checklist_items SET priority = CASE WHEN critical = 1 THEN 'CRITICAL' ELSE 'MEDIUM' END " +
                "WHERE result = 'FAIL' AND priority IS NULL",
        )
        db.execSQL(
            "UPDATE checklist_items SET dueAt = (SELECT COALESCE(i.completedAt, i.startedAt) FROM inspections i " +
                "WHERE i.id = checklist_items.inspectionId) + (CASE WHEN critical = 1 THEN 1 ELSE 7 END) * ${FindingDefaults.DAY_MILLIS} " +
                "WHERE result = 'FAIL' AND dueAt IS NULL",
        )
        db.execSQL(
            "UPDATE checklist_items SET responsibleName = (SELECT i.inspectorName FROM inspections i " +
                "WHERE i.id = checklist_items.inspectionId) WHERE result = 'FAIL' AND responsibleName IS NULL",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO corrective_actions (inspectionId, itemId, description, priority, dueAt, status, " +
                "responsibleName, createdByName, createdAt, updatedAt) " +
                "SELECT ci.inspectionId, ci.id, ci.note, ci.priority, ci.dueAt, 'OPEN', ci.responsibleName, i.inspectorName, " +
                "i.completedAt, i.completedAt FROM checklist_items ci JOIN inspections i ON i.id = ci.inspectionId " +
                "WHERE ci.result = 'FAIL' AND i.completedAt IS NOT NULL",
        )
        db.execSQL(
            "INSERT INTO action_events (actionId, fromStatus, toStatus, byName, at, note) " +
                "SELECT ca.id, NULL, 'OPEN', ca.createdByName, ca.createdAt, 'Created when the app was upgraded' FROM corrective_actions ca",
        )
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, values: ContentValues): Long =
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
}
