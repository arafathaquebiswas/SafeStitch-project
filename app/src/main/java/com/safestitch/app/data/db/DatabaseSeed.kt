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
        DatabaseSeed.seedSampleCapturedInspections(db)
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

    /**
     * Populates a fresh install with sample factory locations, QR-tagged equipment checkpoints,
     * completed inspections, and corrective actions so the dashboard and history are immediately usable.
     */
    fun seedSampleData(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val day = FindingDefaults.DAY_MILLIS

        val factoryId = insert(db, "factories", ContentValues().apply { put("name", "Dhaka Plant 1") })
        val mainBuildingId = insert(db, "buildings", ContentValues().apply {
            put("factoryId", factoryId)
            put("name", "Main Building")
        })
        val annexBuildingId = insert(db, "buildings", ContentValues().apply {
            put("factoryId", factoryId)
            put("name", "Annex Building")
        })
        val floor1Id = insert(db, "floors", ContentValues().apply {
            put("buildingId", mainBuildingId)
            put("name", "Floor 1")
        })
        val floor2Id = insert(db, "floors", ContentValues().apply {
            put("buildingId", mainBuildingId)
            put("name", "Floor 2")
        })
        val groundFloorId = insert(db, "floors", ContentValues().apply {
            put("buildingId", annexBuildingId)
            put("name", "Ground Floor")
        })

        val cuttingAreaId = insert(db, "areas", ContentValues().apply {
            put("floorId", floor1Id)
            put("name", "Cutting Room")
        })
        val finishingAreaId = insert(db, "areas", ContentValues().apply {
            put("floorId", floor1Id)
            put("name", "Finishing")
        })
        val sewingAreaId = insert(db, "areas", ContentValues().apply {
            put("floorId", floor2Id)
            put("name", "Sewing Line A")
        })
        val receptionAreaId = insert(db, "areas", ContentValues().apply {
            put("floorId", groundFloorId)
            put("name", "Reception & Boiler Bay")
        })

        val fe101Id = insert(db, "checkpoints", ContentValues().apply {
            put("code", "FE-101")
            put("name", "CO2 Extinguisher — Cutting North")
            put("area", "Near Pillar B2")
            put("type", CheckpointType.FIRE_EXTINGUISHER.name)
            put("createdAt", now - 14 * day)
            put("areaId", cuttingAreaId)
        })
        val el204Id = insert(db, "checkpoints", ContentValues().apply {
            put("code", "EL-204")
            put("name", "Main Distribution Panel DB-2")
            put("area", "East Wall")
            put("type", CheckpointType.ELECTRICAL.name)
            put("createdAt", now - 14 * day)
            put("areaId", sewingAreaId)
        })
        val ex102Id = insert(db, "checkpoints", ContentValues().apply {
            put("code", "EX-102")
            put("name", "Stairwell Exit West")
            put("area", "Door W-02")
            put("type", CheckpointType.EMERGENCY_EXIT.name)
            put("createdAt", now - 14 * day)
            put("areaId", finishingAreaId)
        })
        insert(db, "checkpoints", ContentValues().apply {
            put("code", "FA-301")
            put("name", "Addressable Fire Alarm Panel")
            put("area", "Guard Desk")
            put("type", CheckpointType.FIRE_ALARM.name)
            put("createdAt", now - 10 * day)
            put("areaId", receptionAreaId)
        })
        insert(db, "checkpoints", ContentValues().apply {
            put("code", "BL-001")
            put("name", "Steam Generator Boiler #1")
            put("area", "Bay 1")
            put("type", CheckpointType.BOILER.name)
            put("createdAt", now - 10 * day)
            put("areaId", receptionAreaId)
        })

        // Sample passed inspection on FE-101
        val insp1Time = now - 2 * day
        val insp1Id = insert(db, "inspections", ContentValues().apply {
            put("checkpointId", fe101Id)
            put("inspectorName", "Nadia Rahman")
            put("siteName", "Dhaka Plant 1 › Main Building › Floor 1 › Cutting Room")
            put("startedAt", insp1Time - 600_000L)
            put("completedAt", insp1Time)
            put("generalNotes", "Routine weekly extinguisher inspection passed.")
            put("equipmentCode", "FE-101")
            put("equipmentName", "CO2 Extinguisher — Cutting North")
            put("equipmentType", CheckpointType.FIRE_EXTINGUISHER.name)
            put("updatedAt", insp1Time)
        })
        ChecklistTemplates.forType(CheckpointType.FIRE_EXTINGUISHER).forEachIndexed { index, q ->
            insert(db, "checklist_items", ContentValues().apply {
                put("inspectionId", insp1Id)
                put("position", index)
                put("section", q.section)
                put("question", q.question)
                put("critical", if (q.critical) 1 else 0)
                put("result", "PASS")
                put("note", "")
            })
        }

        // Sample inspection with a failed item & open corrective action on EX-102
        val insp2Time = now - day
        val insp2Id = insert(db, "inspections", ContentValues().apply {
            put("checkpointId", ex102Id)
            put("inspectorName", "Nadia Rahman")
            put("siteName", "Dhaka Plant 1 › Main Building › Floor 1 › Finishing")
            put("startedAt", insp2Time - 900_000L)
            put("completedAt", insp2Time)
            put("generalNotes", "Exit corridor partially obstructed by fabric cartons.")
            put("equipmentCode", "EX-102")
            put("equipmentName", "Stairwell Exit West")
            put("equipmentType", CheckpointType.EMERGENCY_EXIT.name)
            put("updatedAt", insp2Time)
        })
        ChecklistTemplates.forType(CheckpointType.EMERGENCY_EXIT).forEachIndexed { index, q ->
            val failed = index == 1
            val itemId = insert(db, "checklist_items", ContentValues().apply {
                put("inspectionId", insp2Id)
                put("position", index)
                put("section", q.section)
                put("question", q.question)
                put("critical", if (q.critical) 1 else 0)
                put("result", if (failed) "FAIL" else "PASS")
                put("note", if (failed) "3 fabric rolls stacked in exit corridor near Door W-02" else "")
                if (failed) {
                    put("priority", "CRITICAL")
                    put("dueAt", now + day)
                    put("responsibleName", "Floor Supervisor Karim")
                }
            })
            if (failed) {
                val actionId = insert(db, "corrective_actions", ContentValues().apply {
                    put("inspectionId", insp2Id)
                    put("itemId", itemId)
                    put("description", "3 fabric rolls stacked in exit corridor near Door W-02")
                    put("priority", "CRITICAL")
                    put("dueAt", now + day)
                    put("status", "OPEN")
                    put("responsibleName", "Floor Supervisor Karim")
                    put("createdByName", "Nadia Rahman")
                    put("createdAt", insp2Time)
                    put("updatedAt", insp2Time)
                })
                insert(db, "action_events", ContentValues().apply {
                    put("actionId", actionId)
                    putNull("fromStatus")
                    put("toStatus", "OPEN")
                    put("byName", "Nadia Rahman")
                    put("at", insp2Time)
                    put("note", "Created when inspection was submitted")
                })
            }
        }

        // Sample inspection on EL-204 with resolved action awaiting admin verification
        val insp3Time = now - 3 * day
        val insp3Id = insert(db, "inspections", ContentValues().apply {
            put("checkpointId", el204Id)
            put("inspectorName", "Kamal Hossain")
            put("siteName", "Dhaka Plant 1 › Main Building › Floor 2 › Sewing Line A")
            put("startedAt", insp3Time - 600_000L)
            put("completedAt", insp3Time)
            put("generalNotes", "Insulating mat missing in front of panel DB-2.")
            put("equipmentCode", "EL-204")
            put("equipmentName", "Main Distribution Panel DB-2")
            put("equipmentType", CheckpointType.ELECTRICAL.name)
            put("updatedAt", insp3Time)
        })
        ChecklistTemplates.forType(CheckpointType.ELECTRICAL).forEachIndexed { index, q ->
            val failed = index == 4
            val itemId = insert(db, "checklist_items", ContentValues().apply {
                put("inspectionId", insp3Id)
                put("position", index)
                put("section", q.section)
                put("question", q.question)
                put("critical", if (q.critical) 1 else 0)
                put("result", if (failed) "FAIL" else "PASS")
                put("note", if (failed) "Insulating rubber mat removed during floor cleaning" else "")
                if (failed) {
                    put("priority", "HIGH")
                    put("dueAt", now + 2 * day)
                    put("responsibleName", "Maintenance Lead Rafiq")
                }
            })
            if (failed) {
                val resolvedTime = now - 3_600_000L
                val actionId = insert(db, "corrective_actions", ContentValues().apply {
                    put("inspectionId", insp3Id)
                    put("itemId", itemId)
                    put("description", "Insulating rubber mat removed during floor cleaning")
                    put("priority", "HIGH")
                    put("dueAt", now + 2 * day)
                    put("status", "RESOLVED")
                    put("responsibleName", "Maintenance Lead Rafiq")
                    put("createdByName", "Kamal Hossain")
                    put("createdAt", insp3Time)
                    put("startedByName", "Maintenance Lead Rafiq")
                    put("startedAt", insp3Time + 3_600_000L)
                    put("resolutionNote", "Installed new IEC Class-2 insulating rubber mat in front of DB-2")
                    put("resolvedByName", "Maintenance Lead Rafiq")
                    put("resolvedAt", resolvedTime)
                    put("updatedAt", resolvedTime)
                })
                insert(db, "action_events", ContentValues().apply {
                    put("actionId", actionId)
                    putNull("fromStatus")
                    put("toStatus", "OPEN")
                    put("byName", "Kamal Hossain")
                    put("at", insp3Time)
                    put("note", "Created when inspection was submitted")
                })
                insert(db, "action_events", ContentValues().apply {
                    put("actionId", actionId)
                    put("fromStatus", "OPEN")
                    put("toStatus", "IN_PROGRESS")
                    put("byName", "Maintenance Lead Rafiq")
                    put("at", insp3Time + 3_600_000L)
                    put("note", "Ordered replacement insulating mat from store")
                })
                insert(db, "action_events", ContentValues().apply {
                    put("actionId", actionId)
                    put("fromStatus", "IN_PROGRESS")
                    put("toStatus", "RESOLVED")
                    put("byName", "Maintenance Lead Rafiq")
                    put("at", resolvedTime)
                    put("note", "Installed new IEC Class-2 insulating rubber mat in front of DB-2")
                })
            }
        }

        seedSampleCapturedInspections(db, now)
    }

    /**
     * Populates `captured_inspections` with 3 sample rows so the Captured Inspection Photos screen
     * has sample entries on first launch and after upgrading from v1.
     */
    fun seedSampleCapturedInspections(db: SupportSQLiteDatabase, now: Long = System.currentTimeMillis()) {
        val existingCount = db.query("SELECT COUNT(*) FROM captured_inspections").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (existingCount > 0L) return

        insert(db, "captured_inspections", ContentValues().apply {
            put("timestamp", now - 3_600_000L * 2)
            put("imagePath", "photos/IMG_FE101_gauge_check.jpg")
            put("statusTags", "PASS")
        })
        insert(db, "captured_inspections", ContentValues().apply {
            put("timestamp", now - 3_600_000L)
            put("imagePath", "photos/IMG_EX102_corridor_obstruction.jpg")
            put("statusTags", "FAIL,FOLLOW_UP")
        })
        insert(db, "captured_inspections", ContentValues().apply {
            put("timestamp", now - 900_000L)
            put("imagePath", "photos/IMG_EL204_panel_mat.jpg")
            put("statusTags", "NEEDS_REVIEW")
        })
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, values: ContentValues): Long =
        db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
}
