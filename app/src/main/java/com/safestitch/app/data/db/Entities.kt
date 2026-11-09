package com.safestitch.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemOutcome
import com.safestitch.app.domain.ItemResult

/** A physical place or piece of equipment with a QR label (e.g. "Line 4", "Fire extinguisher FE-12"). */
@Entity(tableName = "checkpoints", indices = [Index(value = ["code"], unique = true)])
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val area: String,
    val type: CheckpointType,
    val createdAt: Long,
)

@Entity(
    tableName = "inspections",
    foreignKeys = [
        ForeignKey(
            entity = CheckpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkpointId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("checkpointId")],
)
data class InspectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkpointId: Long,
    val inspectorName: String,
    val siteName: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val generalNotes: String = "",
    val reportFileName: String? = null,
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [
        ForeignKey(
            entity = InspectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
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
) {
    fun toOutcome() = ItemOutcome(result = result, critical = critical, note = note)
}

/** A photo stored in the app's private files/photos folder. [itemId] is null for general photos. */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = InspectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
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

data class InspectionWithDetails(
    @Embedded val inspection: InspectionEntity,
    @Relation(parentColumn = "checkpointId", entityColumn = "id")
    val checkpoint: CheckpointEntity,
    @Relation(parentColumn = "id", entityColumn = "inspectionId")
    val items: List<ChecklistItemEntity>,
    @Relation(parentColumn = "id", entityColumn = "inspectionId")
    val photos: List<PhotoEntity>,
) {
    val sortedItems: List<ChecklistItemEntity> get() = items.sortedBy { it.position }
    val sortedPhotos: List<PhotoEntity> get() = photos.sortedBy { it.takenAt }
    val isCompleted: Boolean get() = inspection.completedAt != null

    fun summary(): InspectionSummary = InspectionSummary.of(items.map { it.toOutcome() })
}
