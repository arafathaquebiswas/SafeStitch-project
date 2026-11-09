package com.safestitch.app.data

import androidx.room.withTransaction
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.InspectionEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DuplicateCheckpointCodeException(code: String) :
    Exception("A checkpoint with code $code already exists.")

/** Single source of truth for checkpoints, inspections and photos. Everything is stored on the device. */
class InspectionRepository(
    private val database: SafeStitchDatabase,
    private val photoStorage: PhotoStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val checkpoints = database.checkpointDao()
    private val inspections = database.inspectionDao()

    fun observeInspections(): Flow<List<InspectionWithDetails>> = inspections.observeAll()
    fun observeInspection(id: Long): Flow<InspectionWithDetails?> = inspections.observe(id)
    suspend fun getInspection(id: Long): InspectionWithDetails? = inspections.get(id)

    fun observeCheckpoints(): Flow<List<CheckpointEntity>> = checkpoints.observeAll()
    suspend fun getCheckpoints(): List<CheckpointEntity> = checkpoints.getAll()
    suspend fun getCheckpoint(id: Long): CheckpointEntity? = checkpoints.getById(id)

    suspend fun findCheckpointByCode(code: String): CheckpointEntity? =
        QrPayload.parseCheckpointCode(code)?.let { checkpoints.findByCode(it) }

    suspend fun createCheckpoint(code: String, name: String, area: String, type: CheckpointType): Long {
        val normalized = QrPayload.parseCheckpointCode(code)
            ?: throw IllegalArgumentException("Checkpoint code is empty or too long.")
        return database.withTransaction {
            if (checkpoints.findByCode(normalized) != null) throw DuplicateCheckpointCodeException(normalized)
            checkpoints.insert(
                CheckpointEntity(code = normalized, name = name.trim(), area = area.trim(), type = type, createdAt = clock()),
            )
        }
    }

    suspend fun updateCheckpoint(id: Long, name: String, area: String, type: CheckpointType) {
        val existing = checkpoints.getById(id) ?: return
        checkpoints.update(existing.copy(name = name.trim(), area = area.trim(), type = type))
    }

    /** Re-opens the checkpoint's unfinished inspection, or starts a new one from its checklist template. */
    suspend fun startOrResumeInspection(checkpointId: Long, inspectorName: String, siteName: String): Long =
        database.withTransaction {
            inspections.findOpenDraft(checkpointId)?.let { return@withTransaction it.id }
            val checkpoint = checkpoints.getById(checkpointId)
                ?: throw IllegalArgumentException("Unknown checkpoint $checkpointId")
            val inspectionId = inspections.insertInspection(
                InspectionEntity(
                    checkpointId = checkpointId,
                    inspectorName = inspectorName.trim(),
                    siteName = siteName.trim(),
                    startedAt = clock(),
                ),
            )
            inspections.insertItems(
                ChecklistTemplates.forType(checkpoint.type).mapIndexed { index, template ->
                    ChecklistItemEntity(
                        inspectionId = inspectionId,
                        position = index,
                        section = template.section,
                        question = template.question,
                        critical = template.critical,
                    )
                },
            )
            inspectionId
        }

    suspend fun setItemResult(itemId: Long, result: ItemResult) = inspections.setItemResult(itemId, result)
    suspend fun setItemNote(itemId: Long, note: String) = inspections.setItemNote(itemId, note)
    suspend fun updateHeader(inspectionId: Long, inspectorName: String, siteName: String) =
        inspections.updateHeader(inspectionId, inspectorName.trim(), siteName.trim())
    suspend fun setGeneralNotes(inspectionId: Long, notes: String) = inspections.setGeneralNotes(inspectionId, notes)

    suspend fun addPhoto(inspectionId: Long, itemId: Long?, fileName: String): Long =
        inspections.insertPhoto(PhotoEntity(inspectionId = inspectionId, itemId = itemId, fileName = fileName, takenAt = clock()))

    suspend fun deletePhoto(photoId: Long) {
        val photo = inspections.getPhoto(photoId) ?: return
        inspections.deletePhoto(photo)
        withContext(Dispatchers.IO) { photoStorage.delete(photo.fileName) }
    }

    suspend fun markCompleted(inspectionId: Long, completedAt: Long, reportFileName: String) =
        inspections.markCompleted(inspectionId, completedAt, reportFileName)

    suspend fun setReportFile(inspectionId: Long, reportFileName: String) =
        inspections.setReportFile(inspectionId, reportFileName)

    /** Deletes the inspection and its photos. Returns the PDF file name so the caller can remove it. */
    suspend fun deleteInspection(inspectionId: Long): String? {
        val details = inspections.get(inspectionId) ?: return null
        inspections.delete(inspectionId)
        withContext(Dispatchers.IO) { details.photos.forEach { photoStorage.delete(it.fileName) } }
        return details.inspection.reportFileName
    }
}
