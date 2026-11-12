package com.safestitch.app.data

import androidx.room.withTransaction
import com.safestitch.app.data.db.ActionEventEntity
import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.CorrectiveActionEntity
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.InspectionEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.pdf.CapturedEquipmentDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DuplicateCheckpointCodeException(code: String) :
    Exception("Equipment with code $code already exists.")

data class SubmitResult(val createdActions: Int, val alreadySubmitted: Boolean)

/** A corrective action ready to upload: the row plus its audit trail and after-fix photos. */
data class ActionSyncBundle(val row: ActionRow, val events: List<ActionEventEntity>, val photos: List<PhotoEntity>)

enum class SyncKind(val label: String) { INSPECTION("Inspection"), CORRECTIVE_ACTION("Corrective action"), PHOTO("Photo") }

/** A record the server reported as conflicting; shown on the Sync screen until an admin decides. */
data class SyncConflict(val kind: SyncKind, val localId: Long, val label: String, val message: String)

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
    suspend fun countInspectionsFor(checkpointId: Long): Int = inspections.countForCheckpoint(checkpointId)

    // --- captured camera inspections (organized by status tags) -----------------------------------

    fun observeCapturedInspections(): Flow<List<Inspection>> = inspections.observeCapturedInspections()

    fun observeCapturedInspection(id: Long): Flow<Inspection?> = inspections.observeCapturedInspection(id)

    suspend fun getCapturedInspection(id: Long): Inspection? = inspections.getCapturedInspection(id)

    fun observeCapturedInspectionsByTag(tag: String): Flow<List<Inspection>> =
        inspections.observeCapturedInspectionsByTag(tag.trim())

    suspend fun recordCapturedInspection(
        imagePath: String,
        statusTags: List<String> = listOf(Inspection.TAG_NEEDS_REVIEW),
        timestamp: Long = clock(),
    ): Long = inspections.insertCapturedInspection(
        Inspection(
            timestamp = timestamp,
            imagePath = imagePath,
            statusTags = statusTags.ifEmpty { listOf(Inspection.TAG_NEEDS_REVIEW) },
        ),
    )

    suspend fun updateCapturedInspectionTags(id: Long, statusTags: List<String>) {
        val cleaned = statusTags.map { Inspection.normalizeTag(it) }.filter { it.isNotEmpty() }.distinct()
        inspections.updateCapturedInspectionTags(
            id = id,
            statusTags = cleaned.ifEmpty { listOf(Inspection.TAG_NEEDS_REVIEW) },
        )
    }

    suspend fun toggleCapturedInspectionTag(inspection: Inspection, tag: String) {
        val updated = inspection.withToggledTag(tag)
        inspections.updateCapturedInspection(updated)
    }

    suspend fun addCapturedInspectionTag(inspection: Inspection, rawTag: String) {
        val updated = inspection.withAddedTag(rawTag)
        if (updated != inspection) {
            inspections.updateCapturedInspection(updated)
        }
    }

    suspend fun removeCapturedInspectionTag(inspection: Inspection, rawTag: String) {
        val updated = inspection.withRemovedTag(rawTag)
        if (updated != inspection) {
            inspections.updateCapturedInspection(updated)
        }
    }

    suspend fun deleteCapturedInspection(id: Long) {
        val existing = inspections.getCapturedInspection(id) ?: return
        inspections.deleteCapturedInspection(id)
        withContext(Dispatchers.IO) {
            runCatching { File(existing.imagePath).takeIf { it.exists() }?.delete() }
        }
    }

    // --- equipment --------------------------------------------------------------------------------

    fun observeCheckpoints(): Flow<List<CheckpointEntity>> = checkpoints.observeAll()
    suspend fun getCheckpoints(): List<CheckpointEntity> = checkpoints.getAll()
    suspend fun getCheckpoint(id: Long): CheckpointEntity? = checkpoints.getById(id)
    fun observeAreaPaths(): Flow<List<AreaPath>> = locations.observeAreaPaths()
    suspend fun getAreaPaths(): List<AreaPath> = locations.getAreaPaths()

    /**
     * Resolves the equipment context for a camera-captured [Inspection] record by checking:
     * 1. An explicit [overrideCheckpointId] selected on the Inspection Details screen.
     * 2. An exact photo or path match in an existing [InspectionWithDetails].
     * 3. A matching checkpoint code in the inspection's tags or image file name, or the latest/first registered checkpoint.
     * 4. Deterministic fallback details if no checkpoints are registered yet.
     */
    suspend fun resolveCapturedEquipmentDetails(
        inspection: Inspection,
        overrideCheckpointId: Long? = null,
        activeInspectorName: String? = null,
    ): CapturedEquipmentDetails {
        val allCheckpoints = checkpoints.getAll()
        val areaPaths = locations.getAreaPaths()
        val allInspections = inspections.observeAll().first()
        return resolveEquipmentFromCatalog(
            inspection = inspection,
            allCheckpoints = allCheckpoints,
            areaPaths = areaPaths,
            allInspections = allInspections,
            overrideCheckpointId = overrideCheckpointId,
            activeInspectorName = activeInspectorName,
        )
    }

    companion object {
        fun resolveEquipmentFromCatalog(
            inspection: Inspection,
            allCheckpoints: List<CheckpointEntity>,
            areaPaths: List<AreaPath>,
            allInspections: List<InspectionWithDetails>,
            overrideCheckpointId: Long? = null,
            activeInspectorName: String? = null,
        ): CapturedEquipmentDetails {
            val areaById = areaPaths.associateBy { it.areaId }
            val fileName = File(inspection.imagePath).name
            val lowerPath = inspection.imagePath.lowercase(Locale.ROOT)
            val latestInspector = activeInspectorName?.takeIf { it.isNotBlank() }
                ?: allInspections.firstOrNull { it.inspection.inspectorName.isNotBlank() }?.inspection?.inspectorName
                ?: "Field Safety Inspector"

            // 1. User-selected equipment override
            if (overrideCheckpointId != null) {
                val chosen = allCheckpoints.firstOrNull { it.id == overrideCheckpointId }
                if (chosen != null) {
                    val latestForCheckpoint = allInspections.firstOrNull { it.checkpoint.id == chosen.id }
                    return buildCheckpointDetails(
                        checkpoint = chosen,
                        areaById = areaById,
                        matchingInspection = latestForCheckpoint,
                        inspectorFallback = latestInspector,
                        autoLinked = false,
                    )
                }
            }

            // 2. Direct photo match in an existing equipment inspection
            val matchedInspection = allInspections.firstOrNull { details ->
                details.inspection.imagePath.equals(inspection.imagePath, ignoreCase = true) ||
                    (fileName.isNotBlank() && details.photos.any { it.fileName.equals(fileName, ignoreCase = true) })
            }
            if (matchedInspection != null) {
                return buildCheckpointDetails(
                    checkpoint = matchedInspection.checkpoint,
                    areaById = areaById,
                    matchingInspection = matchedInspection,
                    inspectorFallback = latestInspector,
                    autoLinked = true,
                )
            }

            // 3. Tag or filename match against registered checkpoints
            val tagOrFileCheckpoint = allCheckpoints.firstOrNull { cp ->
                val normCode = Inspection.normalizeTag(cp.code)
                inspection.normalizedTags.any { tag ->
                    tag.equals(cp.code, ignoreCase = true) ||
                        tag.equals(normCode, ignoreCase = true) ||
                        tag.equals("EQUIP_$normCode", ignoreCase = true)
                } || lowerPath.contains(cp.code.lowercase(Locale.ROOT))
            } ?: when {
                lowerPath.contains("fe12") || lowerPath.contains("fire") ->
                    allCheckpoints.firstOrNull { it.type == CheckpointType.FIRE_SAFETY }
                lowerPath.contains("ep04") || lowerPath.contains("electrical") ->
                    allCheckpoints.firstOrNull { it.type == CheckpointType.ELECTRICAL }
                lowerPath.contains("mg09") || lowerPath.contains("guard") || lowerPath.contains("sewing") ->
                    allCheckpoints.firstOrNull { it.type == CheckpointType.SEWING_LINE }
                else -> null
            } ?: allInspections.firstOrNull()?.checkpoint ?: allCheckpoints.firstOrNull()

            if (tagOrFileCheckpoint != null) {
                val latestForCheckpoint = allInspections.firstOrNull { it.checkpoint.id == tagOrFileCheckpoint.id }
                return buildCheckpointDetails(
                    checkpoint = tagOrFileCheckpoint,
                    areaById = areaById,
                    matchingInspection = latestForCheckpoint,
                    inspectorFallback = latestInspector,
                    autoLinked = true,
                )
            }

            return CapturedEquipmentDetails.fromInspectionFallback(inspection, latestInspector)
        }

        private fun buildCheckpointDetails(
            checkpoint: CheckpointEntity,
            areaById: Map<Long, AreaPath>,
            matchingInspection: InspectionWithDetails?,
            inspectorFallback: String,
            autoLinked: Boolean,
        ): CapturedEquipmentDetails {
            val hierarchyLabel = checkpoint.areaId?.let { areaById[it]?.label }.orEmpty()
            val location = listOf(
                matchingInspection?.inspection?.siteName.orEmpty().ifBlank { hierarchyLabel },
                checkpoint.area,
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                .ifBlank { "Main Factory › Production Floor" }

            val name = matchingInspection?.equipmentName?.takeIf { it.isNotBlank() } ?: checkpoint.name
            val code = matchingInspection?.equipmentCode?.takeIf { it.isNotBlank() } ?: checkpoint.code
            val category = (matchingInspection?.equipmentType ?: checkpoint.type).label
            val inspector = matchingInspection?.inspection?.inspectorName?.takeIf { it.isNotBlank() } ?: inspectorFallback

            return CapturedEquipmentDetails(
                equipmentName = name,
                equipmentCode = code,
                equipmentCategory = category,
                locationPath = location,
                inspectorName = inspector,
                checkpointId = checkpoint.id,
                autoLinked = autoLinked,
            )
        }
    }

    suspend fun findCheckpointByCode(code: String): CheckpointEntity? =
        QrPayload.parseCheckpointCode(code)?.let { checkpoints.findByCode(it) }

    suspend fun createCheckpoint(code: String, name: String, spot: String, type: CheckpointType, areaId: Long? = null): Long {
        val normalized = QrPayload.parseCheckpointCode(code)
            ?: throw IllegalArgumentException("Equipment code is empty or too long.")
        return database.withTransaction {
            if (checkpoints.findByCode(normalized) != null) throw DuplicateCheckpointCodeException(normalized)
            checkpoints.insert(
                CheckpointEntity(code = normalized, name = name.trim(), area = spot.trim(), type = type, createdAt = clock(), areaId = areaId),
            )
        }
    }

    suspend fun updateCheckpoint(id: Long, name: String, spot: String, type: CheckpointType, areaId: Long?) {
        val existing = checkpoints.getById(id) ?: return
        checkpoints.update(existing.copy(name = name.trim(), area = spot.trim(), type = type, areaId = areaId))
    }

    /**
     * Re-opens the equipment's unfinished inspection, or starts a new one. The new inspection is a snapshot:
     * the current questions, the equipment's code/name/type and its location are copied into it.
     */
    suspend fun startOrResumeInspection(checkpointId: Long, inspectorName: String, inspectorUserId: Long?): Long =
        database.withTransaction {
            inspections.findOpenDraft(checkpointId)?.let { return@withTransaction it.id }
            val checkpoint = checkpoints.getById(checkpointId)
                ?: throw IllegalArgumentException("Unknown equipment $checkpointId")
            val template = questions.activeForType(checkpoint.type)
            check(template.isNotEmpty()) {
                "There are no active checklist questions for ${checkpoint.type.label}. Ask an admin to add some."
            }
            val now = clock()
            val inspectionId = inspections.insertInspection(
                InspectionEntity(
                    checkpointId = checkpointId,
                    inspectorName = inspectorName.trim(),
                    siteName = checkpoint.areaId?.let { locations.getAreaPath(it)?.label }.orEmpty(),
                    startedAt = now,
                    inspectorUserId = inspectorUserId,
                    equipmentCode = checkpoint.code,
                    equipmentName = checkpoint.name,
                    equipmentType = checkpoint.type,
                    updatedAt = now,
                ),
            )
            inspections.insertItems(
                template.mapIndexed { index, q ->
                    ChecklistItemEntity(inspectionId = inspectionId, position = index, section = q.section, question = q.question, critical = q.critical)
                },
            )
            inspectionId
        }

    /** Records an answer. A new failure gets the default priority, due date and responsible person (the inspector). */
    suspend fun setItemResult(item: ChecklistItemEntity, result: ItemResult) = database.withTransaction {
        inspections.setItemResult(item.id, result)
        if (result == ItemResult.FAIL) {
            val current = inspections.getItem(item.id) ?: return@withTransaction
            if (current.priority == null || current.dueAt == null) {
                val priority = current.priority ?: FindingDefaults.priorityFor(current.critical)
                inspections.setFinding(current.id, priority, current.dueAt ?: FindingDefaults.dueAt(priority, clock()))
            }
            if (current.responsibleName.isNullOrBlank()) {
                inspections.get(current.inspectionId)?.inspection?.let { inspections.setResponsible(current.id, it.inspectorName, it.inspectorUserId) }
            }
        }
    }

    suspend fun setFinding(itemId: Long, priority: Priority, dueAt: Long) = inspections.setFinding(itemId, priority, dueAt)
    suspend fun setResponsible(itemId: Long, name: String, userId: Long?) = inspections.setResponsible(itemId, name.trim(), userId)
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
     * Submits the inspection and creates exactly one corrective action per failed item, in one transaction.
     * Safe to retry: a second call for the same inspection changes nothing and reports [SubmitResult.alreadySubmitted].
     */
    suspend fun submitInspection(inspectionId: Long, completedAt: Long, reportFileName: String): SubmitResult =
        database.withTransaction {
            val details = inspections.get(inspectionId) ?: throw IllegalStateException("Inspection $inspectionId no longer exists.")
            if (details.isCompleted || inspections.markCompleted(inspectionId, completedAt, reportFileName) == 0) {
                return@withTransaction SubmitResult(createdActions = 0, alreadySubmitted = true)
            }
            val inspector = details.inspection.inspectorName
            val failed = details.sortedItems.filter { it.result == ItemResult.FAIL }
            val ids = actions.insertAll(
                failed.map { item ->
                    val priority = item.priority ?: FindingDefaults.priorityFor(item.critical)
                    CorrectiveActionEntity(
                        inspectionId = inspectionId,
                        itemId = item.id,
                        description = item.note.trim(),
                        priority = priority,
                        dueAt = item.dueAt ?: FindingDefaults.dueAt(priority, completedAt),
                        responsibleName = item.responsibleName?.takeIf { it.isNotBlank() } ?: inspector,
                        responsibleUserId = if (item.responsibleName.isNullOrBlank()) details.inspection.inspectorUserId else item.responsibleUserId,
                        createdByName = inspector,
                        createdAt = completedAt,
                        updatedAt = completedAt,
                    )
                },
            )
            ids.zip(failed).filter { it.first != -1L }.forEach { (actionId, item) ->
                actions.insertEvent(
                    ActionEventEntity(
                        actionId = actionId, fromStatus = null, toStatus = ActionStatus.OPEN, byName = inspector,
                        byUserId = details.inspection.inspectorUserId, at = completedAt, note = "Created from failed item ${item.position + 1}",
                    ),
                )
            }
            SubmitResult(createdActions = ids.count { it != -1L }, alreadySubmitted = false)
        }.also { if (!it.alreadySubmitted) onSyncableChange() }

    suspend fun setReportFile(inspectionId: Long, reportFileName: String) = inspections.setReportFile(inspectionId, reportFileName)

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
    fun observeActionEvents(id: Long): Flow<List<ActionEventEntity>> = actions.observeEvents(id)
    suspend fun countActions(): Int = actions.countAll()
    suspend fun countActionsFor(inspectionId: Long): Int = actions.countForInspection(inspectionId)

    /** Open -> In progress. Anyone signed in. */
    suspend fun startAction(actionId: Long, user: UserEntity): Boolean =
        transition(actionId, user, ActionStatus.OPEN, ActionStatus.IN_PROGRESS) { at -> actions.start(actionId, user.displayName, at) }

    /** In progress -> Resolved (awaiting verification). Anyone signed in; a note is required. */
    suspend fun resolveAction(actionId: Long, note: String, user: UserEntity): Boolean {
        require(note.isNotBlank()) { "Describe what was done to fix the problem." }
        return transition(actionId, user, ActionStatus.IN_PROGRESS, ActionStatus.RESOLVED, note.trim()) { at ->
            actions.resolve(actionId, note.trim(), user.displayName, at)
        }
    }

    /** Resolved -> Verified. Admins only. */
    suspend fun verifyAction(actionId: Long, admin: UserEntity): Boolean {
        requireAdmin(admin)
        return transition(actionId, admin, ActionStatus.RESOLVED, ActionStatus.VERIFIED) { at -> actions.verify(actionId, admin.displayName, at) }
    }

    /** Resolved -> In progress, with the admin's reason. Admins only. */
    suspend fun sendBackAction(actionId: Long, admin: UserEntity, comment: String): Boolean {
        requireAdmin(admin)
        require(comment.isNotBlank()) { "Say why the fix was not accepted." }
        return transition(actionId, admin, ActionStatus.RESOLVED, ActionStatus.IN_PROGRESS, comment.trim()) { at ->
            actions.sendBack(actionId, comment.trim(), at)
        }
    }

    suspend fun rescheduleAction(actionId: Long, admin: UserEntity, priority: Priority, dueAt: Long): Boolean {
        requireAdmin(admin)
        val current = actions.get(actionId) ?: return false
        return transition(actionId, admin, current.status, current.status, "Priority ${priority.label}, due date changed") { at ->
            actions.reschedule(actionId, priority, dueAt, at)
        }
    }

    suspend fun reassignAction(actionId: Long, admin: UserEntity, name: String, userId: Long?): Boolean {
        requireAdmin(admin)
        require(name.isNotBlank()) { "Choose the responsible person." }
        val current = actions.get(actionId) ?: return false
        return transition(actionId, admin, current.status, current.status, "Responsible person: ${name.trim()}") { at ->
            actions.reassign(actionId, name.trim(), userId, at)
        }
    }

    /** After-fix photo taken while the action is open or in progress. */
    suspend fun addAfterFixPhoto(actionId: Long, fileName: String): Long = database.withTransaction {
        val action = actions.get(actionId) ?: throw IllegalStateException("Corrective action $actionId no longer exists.")
        check(action.status != ActionStatus.VERIFIED) { "This action is already verified." }
        val at = clock()
        inspections.insertPhoto(PhotoEntity(inspectionId = action.inspectionId, itemId = action.itemId, actionId = actionId, fileName = fileName, takenAt = at))
            .also { actions.touch(actionId, at) }
    }.also { onSyncableChange() }

    private suspend fun transition(
        actionId: Long,
        user: UserEntity,
        from: ActionStatus,
        to: ActionStatus,
        note: String = "",
        change: suspend (at: Long) -> Int,
    ): Boolean = database.withTransaction {
        val at = clock()
        val changed = change(at) == 1
        if (changed) {
            actions.insertEvent(ActionEventEntity(actionId = actionId, fromStatus = from, toStatus = to, byName = user.displayName, byUserId = user.id, at = at, note = note))
        }
        changed
    }.also { if (it) onSyncableChange() }

    private fun requireAdmin(user: UserEntity) = require(user.isAdmin && user.active) { "Only an admin can do this." }

    // --- sync -------------------------------------------------------------------------------------

    suspend fun pendingSyncInspections(limit: Int): List<InspectionWithDetails> = inspections.pendingSync(limit)

    suspend fun pendingSyncActions(limit: Int): List<ActionSyncBundle> {
        val rows = actions.pendingSync(limit)
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.action.id }
        val events = actions.eventsFor(ids).groupBy { it.actionId }
        val photos = actions.afterFixPhotosFor(ids).groupBy { it.actionId }
        return rows.map { ActionSyncBundle(it, events[it.action.id].orEmpty(), photos[it.action.id].orEmpty()) }
    }

    suspend fun pendingPhotos(limit: Int): List<PhotoEntity> = inspections.pendingPhotos(limit)
    suspend fun setPhotoSha(photoId: Long, sha: String) = inspections.setPhotoSha(photoId, sha)
    suspend fun markPhotoSynced(photoId: Long, at: Long) = inspections.markPhotoSynced(photoId, at)
    suspend fun setPhotoConflict(photoId: Long, message: String) = inspections.setPhotoConflict(photoId, message)

    suspend fun markSynced(inspectionIds: List<Long>, actionIds: List<Long>, at: Long) = database.withTransaction {
        if (inspectionIds.isNotEmpty()) inspections.markSynced(inspectionIds, at)
        if (actionIds.isNotEmpty()) actions.markSynced(actionIds, at)
    }

    suspend fun setConflict(kind: SyncKind, localId: Long, message: String) = when (kind) {
        SyncKind.INSPECTION -> inspections.setConflict(localId, message)
        SyncKind.CORRECTIVE_ACTION -> actions.setConflict(localId, message)
        SyncKind.PHOTO -> inspections.setPhotoConflict(localId, message)
    }

    /** Admin decision on a conflict: keep the server's copy, or send this phone's copy again as the newest version. */
    suspend fun resolveConflict(conflict: SyncConflict, sendMine: Boolean) {
        val at = clock()
        when (conflict.kind) {
            SyncKind.INSPECTION -> if (sendMine) inspections.clearConflictForResend(conflict.localId, at) else inspections.acceptServerVersion(conflict.localId)
            SyncKind.CORRECTIVE_ACTION -> if (sendMine) actions.clearConflictForResend(conflict.localId, at) else actions.acceptServerVersion(conflict.localId)
            SyncKind.PHOTO -> if (sendMine) inspections.clearPhotoConflict(conflict.localId) else inspections.acceptServerPhoto(conflict.localId, at)
        }
        if (sendMine) onSyncableChange()
    }

    /** Marks everything as not yet uploaded, so the next sync sends it all again (the server ignores exact duplicates). */
    suspend fun resetSyncState() = database.withTransaction {
        inspections.resetSync()
        actions.resetSync()
        inspections.resetPhotoSync()
    }.also { onSyncableChange() }

    fun observePendingSyncCount(): Flow<Int> =
        combine(inspections.observePendingSyncCount(), actions.observePendingSyncCount()) { a, b -> a + b }

    fun observePendingPhotoCount(): Flow<Int> = inspections.observePendingPhotoCount()

    fun observeConflicts(): Flow<List<SyncConflict>> = combine(
        inspections.observeConflicts(),
        actions.observeConflicts(),
        inspections.observePhotoConflicts(),
    ) { insp, acts, photos ->
        insp.map { SyncConflict(SyncKind.INSPECTION, it.id, "Inspection R${it.id} · ${it.equipmentCode}", it.syncConflict.orEmpty()) } +
            acts.map { SyncConflict(SyncKind.CORRECTIVE_ACTION, it.id, "Corrective action #${it.id} (${it.status.label})", it.syncConflict.orEmpty()) } +
            photos.map { SyncConflict(SyncKind.PHOTO, it.id, "Photo ${it.id} of inspection R${it.inspectionId}", it.syncConflict.orEmpty()) }
    }
}
