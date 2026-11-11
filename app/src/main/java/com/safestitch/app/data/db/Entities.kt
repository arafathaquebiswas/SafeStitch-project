package com.safestitch.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemOutcome
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import com.safestitch.app.domain.UserRole

// ---------------------------------------------------------------------------------------------
//  Accounts (never synced)
// ---------------------------------------------------------------------------------------------

/** Local account. [passwordHash] is a salted PBKDF2 hash (see PasswordHasher); never a password. */
@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val passwordHash: String,
    val active: Boolean = true,
    val createdAt: Long,
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN
}

// ---------------------------------------------------------------------------------------------
//  Location hierarchy: factory > building > floor > area
// ---------------------------------------------------------------------------------------------

@Entity(tableName = "factories")
data class FactoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "buildings",
    foreignKeys = [ForeignKey(entity = FactoryEntity::class, parentColumns = ["id"], childColumns = ["factoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("factoryId")],
)
data class BuildingEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val factoryId: Long, val name: String)

@Entity(
    tableName = "floors",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = ["buildingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("buildingId")],
)
data class FloorEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val buildingId: Long, val name: String)

@Entity(
    tableName = "areas",
    foreignKeys = [ForeignKey(entity = FloorEntity::class, parentColumns = ["id"], childColumns = ["floorId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("floorId")],
)
data class AreaEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val floorId: Long, val name: String)

/** One area with its full path (result of a JOIN, not a table). */
data class AreaPath(
    val areaId: Long,
    val areaName: String,
    val floorId: Long,
    val floorName: String,
    val buildingId: Long,
    val buildingName: String,
    val factoryId: Long,
    val factoryName: String,
) {
    val label: String get() = "$factoryName › $buildingName › $floorName › $areaName"
    val buildingLabel: String get() = "$factoryName › $buildingName"
}

// ---------------------------------------------------------------------------------------------
//  Equipment (QR-tagged checkpoints)
// ---------------------------------------------------------------------------------------------

/**
 * A QR-tagged piece of equipment ("Fire extinguisher FE-12"). [areaId] links it into the location
 * hierarchy; [area] is the optional exact spot. Before database version 2, [area] was the only location.
 */
@Entity(tableName = "checkpoints", indices = [Index(value = ["code"], unique = true), Index("areaId")])
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val area: String,
    val type: CheckpointType,
    val createdAt: Long,
    val areaId: Long? = null,
)

// ---------------------------------------------------------------------------------------------
//  Editable checklist questions (copied into each new inspection)
// ---------------------------------------------------------------------------------------------

@Entity(tableName = "template_questions", indices = [Index(value = ["type", "position"])])
data class TemplateQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: CheckpointType,
    val position: Int,
    val section: String,
    val question: String,
    val critical: Boolean,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
)

// ---------------------------------------------------------------------------------------------
//  Inspections: a historical snapshot (questions, equipment and location are copied at start)
// ---------------------------------------------------------------------------------------------

@Entity(
    tableName = "inspections",
    foreignKeys = [
        ForeignKey(entity = CheckpointEntity::class, parentColumns = ["id"], childColumns = ["checkpointId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("checkpointId")],
)
data class InspectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkpointId: Long,
    val inspectorName: String,
    /** Location path captured when the inspection started (kept even if locations are renamed later). */
    val siteName: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val generalNotes: String = "",
    val reportFileName: String? = null,
    val inspectorUserId: Long? = null,
    /** Equipment snapshot taken when the inspection started (kept even if the equipment is edited later). */
    @ColumnInfo(defaultValue = "") val equipmentCode: String = "",
    @ColumnInfo(defaultValue = "") val equipmentName: String = "",
    val equipmentType: CheckpointType? = null,
    /** Last change relevant to sync; compared with [syncedAt]. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    val syncedAt: Long? = null,
    /** Set when the server reported a conflicting record; shown on the Sync screen. */
    val syncConflict: String? = null,
    /** Capture timestamp, primary camera image file path, and status tags for organizing inspection photos. */
    @ColumnInfo(defaultValue = "0") val timestamp: Long = startedAt,
    @ColumnInfo(defaultValue = "") val imagePath: String = "",
    @ColumnInfo(defaultValue = "") val statusTags: String = "",
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [
        ForeignKey(entity = InspectionEntity::class, parentColumns = ["id"], childColumns = ["inspectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("inspectionId")],
)
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inspectionId: Long,
    val position: Int,
    val section: String,
    val question: String,
    val critical: Boolean,
    val result: ItemResult = ItemResult.UNANSWERED,
    val note: String = "",
    /** Finding details, set when the item fails: priority, due date and the person responsible for the fix. */
    val priority: Priority? = null,
    val dueAt: Long? = null,
    val responsibleName: String? = null,
    val responsibleUserId: Long? = null,
) {
    fun toOutcome() = ItemOutcome(result = result, critical = critical, note = note, priority = priority, dueAt = dueAt)
}

/**
 * A photo stored in the app's private files/photos folder.
 * [itemId] null = general photo. [actionId] set = after-fix photo added while resolving that corrective action.
 */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(entity = InspectionEntity::class, parentColumns = ["id"], childColumns = ["inspectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("inspectionId"), Index("actionId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inspectionId: Long,
    val itemId: Long? = null,
    val fileName: String,
    val takenAt: Long,
    val actionId: Long? = null,
    /** SHA-256 of the file, computed when it is first uploaded. */
    val sha256: String? = null,
    val syncedAt: Long? = null,
    val syncConflict: String? = null,
) {
    val isAfterFix: Boolean get() = actionId != null
}

// ---------------------------------------------------------------------------------------------
//  Corrective actions: exactly one per failed item of a submitted inspection
// ---------------------------------------------------------------------------------------------

@Entity(
    tableName = "corrective_actions",
    foreignKeys = [
        ForeignKey(entity = InspectionEntity::class, parentColumns = ["id"], childColumns = ["inspectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChecklistItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["itemId"], unique = true), Index("inspectionId"), Index("status")],
)
data class CorrectiveActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inspectionId: Long,
    val itemId: Long,
    /** The finding as described by the inspector. */
    val description: String,
    val priority: Priority,
    val dueAt: Long,
    val status: ActionStatus = ActionStatus.OPEN,
    /** Person responsible for the fix. */
    @ColumnInfo(defaultValue = "") val responsibleName: String = "",
    val responsibleUserId: Long? = null,
    @ColumnInfo(defaultValue = "") val createdByName: String = "",
    val createdAt: Long,
    val startedByName: String? = null,
    val startedAt: Long? = null,
    val resolutionNote: String? = null,
    val resolvedByName: String? = null,
    val resolvedAt: Long? = null,
    val verifiedByName: String? = null,
    val verifiedAt: Long? = null,
    /** Admin's reason when a resolution was sent back. */
    val adminComment: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    val syncedAt: Long? = null,
    val syncConflict: String? = null,
)

/** Audit trail: one row per status change of a corrective action (who, when, from, to). */
@Entity(
    tableName = "action_events",
    foreignKeys = [
        ForeignKey(entity = CorrectiveActionEntity::class, parentColumns = ["id"], childColumns = ["actionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("actionId")],
)
data class ActionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionId: Long,
    /** Null for the creation event. */
    val fromStatus: ActionStatus?,
    val toStatus: ActionStatus,
    val byName: String,
    val byUserId: Long? = null,
    val at: Long,
    @ColumnInfo(defaultValue = "") val note: String = "",
)

/** A corrective action with the context needed to show it (result of a JOIN). */
data class ActionRow(
    @Embedded val action: CorrectiveActionEntity,
    val question: String,
    val section: String,
    val itemPosition: Int,
    val checkpointId: Long,
    val checkpointName: String,
    val checkpointCode: String,
    val checkpointAreaId: Long?,
    val spot: String,
    val locationSnapshot: String,
    val inspectorName: String,
    val inspectionCompletedAt: Long?,
)

/** A corrective action with its audit trail and after-fix photos (used by sync). */
data class ActionWithHistory(
    @Embedded val action: CorrectiveActionEntity,
    @Relation(parentColumn = "id", entityColumn = "actionId")
    val events: List<ActionEventEntity>,
    @Relation(parentColumn = "id", entityColumn = "actionId")
    val photos: List<PhotoEntity>,
)

data class InspectionWithDetails(
    @Embedded val inspection: InspectionEntity,
    @Relation(parentColumn = "checkpointId", entityColumn = "id")
    val checkpoint: CheckpointEntity,
    @Relation(parentColumn = "id", entityColumn = "inspectionId")
    val items: List<ChecklistItemEntity>,
    @Relation(parentColumn = "id", entityColumn = "inspectionId")
    val photos: List<PhotoEntity>,
    @Relation(parentColumn = "id", entityColumn = "inspectionId")
    val actions: List<CorrectiveActionEntity>,
) {
    val sortedItems: List<ChecklistItemEntity> get() = items.sortedBy { it.position }
    val sortedPhotos: List<PhotoEntity> get() = photos.sortedBy { it.takenAt }
    val isCompleted: Boolean get() = inspection.completedAt != null

    /** Snapshot values (as inspected), falling back to the current equipment for very old rows. */
    val equipmentName: String get() = inspection.equipmentName.ifBlank { checkpoint.name }
    val equipmentCode: String get() = inspection.equipmentCode.ifBlank { checkpoint.code }
    val equipmentType: CheckpointType get() = inspection.equipmentType ?: checkpoint.type

    fun summary(): InspectionSummary = InspectionSummary.of(items.map { it.toOutcome() })
}
