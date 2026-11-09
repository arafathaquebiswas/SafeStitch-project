package com.safestitch.app.data

import androidx.room.withTransaction
import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.CorrectiveActionEntity
import com.safestitch.app.data.db.InspectionEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import com.safestitch.app.domain.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class DuplicateCheckpointCodeException(code: String) :
    Exception("Equipment with code $code already exists.")

/** Equipment, inspections, photos and corrective actions. Everything is stored on the device first. */
class InspectionRepository(
    private val database: SafeStitchDatabase,
    private val photoStorage: PhotoStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val checkpoints = database.checkpointDao()
    private val inspections = database.inspectionDao()
    private val questions = database.templateQuestionDao()
    private val locations = database.locationDao()
    private val actions = database.correctiveActionDao()

    /** Called after changes that the optional sync should upload (set by AppContainer). */
    var onSyncableChange: () -> Unit = {}

    // --- inspections ------------------------------------------------------------------------------

    fun observeInspections(): Flow<List<InspectionWithDetails>> = inspections.observeAll()
    fun observeInspection(id: Long): Flow<InspectionWithDetails?> = inspections.observe(id)
    suspend fun getInspection(id: Long): InspectionWithDetails? = inspections.get(id)

    // --- equipment --------------------------------------------------------------------------------

    fun observeCheckpoints(): Flow<List<CheckpointEntity>> = checkpoints.observeAll()
    suspend fun getCheckpoints(): List<CheckpointEntity> = checkpoints.getAll()
    suspend fun getCheckpoint(id: Long): CheckpointEntity? = checkpoints.getById(id)

    suspend fun findCheckpointByCode(code: String): CheckpointEntity? =
        QrPayload.parseCheckpointCode(code)?.let { checkpoints.findByCode(it) }

    suspend fun createCheckpoint(code: String, name: String, spot: String, type: CheckpointType, areaId: Long? = null): Long {
        val normalized = QrPayload.parseCheckpointCode(code)
            ?: throw IllegalArgumentException("Equipment code is empty or too long.")
        return database.withTransaction {
            if (checkpoints.findByCode(normalized) != null) throw DuplicateCheckpointCodeException(normalized)
            checkpoints.insert(
                CheckpointEntity(
                    code = normalized,
                    name = name.trim(),
                    area = spot.trim(),
                    type = type,
                    createdAt = clock(),
                    areaId = areaId,
                ),
            )
        }
    }

    suspend fun updateCheckpoint(id: Long, name: String, spot: String, type: CheckpointType, areaId: Long?) {
        val existing = checkpoints.getById(id) ?: return
        checkpoints.update(existing.copy(name = name.trim(), area = spot.trim(), type = type, areaId = areaId))
    }

    /** Re-opens the equipment's unfinished inspection, or starts a new one from the current editable questions. */
    suspend fun startOrResumeInspection(checkpointId: Long, inspectorName: String, inspectorUserId: Long?): Long =
        database.withTransaction {
            inspections.findOpenDraft(checkpointId)?.let { return@withTransaction it.id }
            val checkpoint = checkpoints.getById(checkpointId)
                ?: throw IllegalArgumentException("Unknown equipment $checkpointId")
            val template = questions.activeForType(checkpoint.type)
            check(template.isNotEmpty()) {
                "There are no active checklist questions for ${checkpoint.type.label}. Ask an admin to add some."
            }
            val location = checkpoint.areaId?.let { locations.getAreaPath(it)?.label }.orEmpty()
            val now = clock()
            val inspectionId = inspections.insertInspection(
                InspectionEntity(
                    checkpointId = checkpointId,
                    inspectorName = inspectorName.trim(),
                    siteName = location,
                    startedAt = now,
                    inspectorUserId = inspectorUserId,
                    updatedAt = now,
                ),
            )
            inspections.insertItems(
                template.mapIndexed { index, question ->
                    ChecklistItemEntity(
                        inspectionId = inspectionId,
                        position = index,
                        section = question.section,
                        question = question.question,
                        critical = question.critical,
                    )
                },
            )
            inspectionId
        }

    /** Records an answer. A new failure gets the default priority and due date straight away. */
    suspend fun setItemResult(item: ChecklistItemEntity, result: ItemResult) = database.withTransaction {
        inspections.setItemResult(item.id, result)
        if (result == ItemResult.FAIL && (item.priority == null || item.dueAt == null)) {
            val priority = item.priority ?: FindingDefaults.priorityFor(item.critical)
            inspections.setFinding(item.id, priority, item.dueAt ?: FindingDefaults.dueAt(priority, clock()))
        }
    }

    suspend fun setFinding(itemId: Long, priority: Priority, dueAt: Long) = inspections.setFinding(itemId, priority, dueAt)
    suspend fun setItemNote(itemId: Long, note: String) = inspections.setItemNote(itemId, note)
    suspend fun setGeneralNotes(inspectionId: Long, notes: String) = inspections.setGeneralNotes(inspectionId, notes)

    suspend fun addPhoto(inspectionId: Long, itemId: Long?, fileName: String): Long =
        inspections.insertPhoto(PhotoEntity(inspectionId = inspectionId, itemId = itemId, fileName = fileName, takenAt = clock()))

    fun observePhotosForItem(itemId: Long): Flow<List<PhotoEntity>> = inspections.observePhotosForItem(itemId)

    suspend fun deletePhoto(photoId: Long) {
        val photo = inspections.getPhoto(photoId) ?: return
        inspections.deletePhoto(photo)
        withContext(Dispatchers.IO) { photoStorage.delete(photo.fileName) }
    }

    /**
     * Marks the inspection submitted and creates exactly one corrective action per failed item,
     * in one transaction. Returns the number of corrective actions created.
     */
    suspend fun submitInspection(inspectionId: Long, completedAt: Long, reportFileName: String): Int =
        database.withTransaction {
            check(inspections.markCompleted(inspectionId, completedAt, reportFileName) == 1) {
                "This inspection was already submitted."
            }
            val failed = inspections.get(inspectionId)!!.items.filter { it.result == ItemResult.FAIL }
            actions.insertAll(
                failed.map { item ->
                    val priority = item.priority ?: FindingDefaults.priorityFor(item.critical)
                    CorrectiveActionEntity(
                        inspectionId = inspectionId,
                        itemId = item.id,
                        description = item.note.trim(),
                        priority = priority,
                        dueAt = item.dueAt ?: FindingDefaults.dueAt(priority, completedAt),
                        createdAt = completedAt,
                        updatedAt = completedAt,
                    )
                },
            ).count { it != -1L }
        }.also { onSyncableChange() }

    suspend fun setReportFile(inspectionId: Long, reportFileName: String) =
        inspections.setReportFile(inspectionId, reportFileName)

    /** Deletes the inspection and its photos. Returns the PDF file name so the caller can remove it. */
    suspend fun deleteInspection(inspectionId: Long): String? {
        val details = inspections.get(inspectionId) ?: return null
        inspections.delete(inspectionId)
        withContext(Dispatchers.IO) { details.photos.forEach { photoStorage.delete(it.fileName) } }
        return details.inspection.reportFileName
    }

    // --- corrective actions -----------------------------------------------------------------------

    fun observeActionRows(): Flow<List<ActionRow>> = actions.observeRows()
    fun observeActionRow(id: Long): Flow<ActionRow?> = actions.observeRow(id)
    suspend fun countActions(): Int = actions.countAll()

    /** Anyone signed in can report the fix; it then waits for an admin. */
    suspend fun resolveAction(actionId: Long, note: String, user: UserEntity): Boolean {
        require(note.isNotBlank()) { "Describe what was done to fix the problem." }
        return (actions.resolve(actionId, note.trim(), user.displayName, clock()) == 1).also { if (it) onSyncableChange() }
    }

    suspend fun verifyAction(actionId: Long, admin: UserEntity): Boolean {
        requireAdmin(admin)
        return (actions.verify(actionId, admin.displayName, clock()) == 1).also { if (it) onSyncableChange() }
    }

    suspend fun reopenAction(actionId: Long, admin: UserEntity, comment: String): Boolean {
        requireAdmin(admin)
        require(comment.isNotBlank()) { "Say why the fix was not accepted." }
        return (actions.reopen(actionId, comment.trim(), clock()) == 1).also { if (it) onSyncableChange() }
    }

    suspend fun rescheduleAction(actionId: Long, admin: UserEntity, priority: Priority, dueAt: Long): Boolean {
        requireAdmin(admin)
        return (actions.reschedule(actionId, priority, dueAt, clock()) == 1).also { if (it) onSyncableChange() }
    }

    private fun requireAdmin(user: UserEntity) = require(user.isAdmin && user.active) { "Only an admin can do this." }

    // --- sync -------------------------------------------------------------------------------------

    suspend fun pendingSyncInspections(limit: Int): List<InspectionWithDetails> = inspections.pendingSync(limit)
    suspend fun pendingSyncActions(limit: Int): List<ActionRow> = actions.pendingSync(limit)

    suspend fun markSynced(inspectionIds: List<Long>, actionIds: List<Long>, at: Long) = database.withTransaction {
        if (inspectionIds.isNotEmpty()) inspections.markSynced(inspectionIds, at)
        if (actionIds.isNotEmpty()) actions.markSynced(actionIds, at)
    }

    fun observePendingSyncCount(): Flow<Int> =
        combine(inspections.observePendingSyncCount(), actions.observePendingSyncCount()) { a, b -> a + b }
}
