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
//  Accounts
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
 * A QR-tagged piece of equipment or place ("Fire extinguisher FE-12", "Sewing line 4").
 * [areaId] links it into the location hierarchy; [area] is the optional exact spot
 * ("next to stair B"). Before database version 2, [area] was the only location field.
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
//  Inspections
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
    /** Last change relevant to sync; compared with [syncedAt]. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    val syncedAt: Long? = null,
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
    /** Finding priority and due date, set when the item fails. */
    val priority: Priority? = null,
    val dueAt: Long? = null,
) {
    fun toOutcome() = ItemOutcome(result = result, critical = critical, note = note, priority = priority, dueAt = dueAt)
}

/** A photo stored in the app's private files/photos folder. [itemId] is null for general photos. */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(entity = InspectionEntity::class, parentColumns = ["id"], childColumns = ["inspectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("inspectionId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inspectionId: Long,
    val itemId: Long? = null,
    val fileName: String,
    val takenAt: Long,
)

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
    val createdAt: Long,
    val resolutionNote: String? = null,
    val resolvedByName: String? = null,
    val resolvedAt: Long? = null,
    val verifiedByName: String? = null,
    val verifiedAt: Long? = null,
    /** Admin's reason when sending a resolution back. */
    val adminComment: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    val syncedAt: Long? = null,
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

    fun summary(): InspectionSummary = InspectionSummary.of(items.map { it.toOutcome() })
}
