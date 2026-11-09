package com.safestitch.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT * FROM users ORDER BY active DESC, role, displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND active = 1")
    suspend fun countActiveAdmins(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM factories ORDER BY name COLLATE NOCASE")
    fun observeFactories(): Flow<List<FactoryEntity>>

    @Query("SELECT * FROM buildings WHERE factoryId = :factoryId ORDER BY name COLLATE NOCASE")
    fun observeBuildings(factoryId: Long): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM floors WHERE buildingId = :buildingId ORDER BY name COLLATE NOCASE")
    fun observeFloors(buildingId: Long): Flow<List<FloorEntity>>

    @Query("SELECT * FROM areas WHERE floorId = :floorId ORDER BY name COLLATE NOCASE")
    fun observeAreas(floorId: Long): Flow<List<AreaEntity>>

    @Insert suspend fun insertFactory(factory: FactoryEntity): Long
    @Insert suspend fun insertBuilding(building: BuildingEntity): Long
    @Insert suspend fun insertFloor(floor: FloorEntity): Long
    @Insert suspend fun insertArea(area: AreaEntity): Long

    @Query("UPDATE factories SET name = :name WHERE id = :id") suspend fun renameFactory(id: Long, name: String)
    @Query("UPDATE buildings SET name = :name WHERE id = :id") suspend fun renameBuilding(id: Long, name: String)
    @Query("UPDATE floors SET name = :name WHERE id = :id") suspend fun renameFloor(id: Long, name: String)
    @Query("UPDATE areas SET name = :name WHERE id = :id") suspend fun renameArea(id: Long, name: String)

    @Query("$PATH_SELECT ORDER BY fa.name COLLATE NOCASE, b.name COLLATE NOCASE, f.name COLLATE NOCASE, a.name COLLATE NOCASE")
    fun observeAreaPaths(): Flow<List<AreaPath>>

    @Query("$PATH_SELECT WHERE a.id = :areaId")
    suspend fun getAreaPath(areaId: Long): AreaPath?

    @Query(PATH_SELECT)
    suspend fun getAreaPaths(): List<AreaPath>

    companion object {
        const val PATH_SELECT =
            "SELECT a.id AS areaId, a.name AS areaName, f.id AS floorId, f.name AS floorName, " +
                "b.id AS buildingId, b.name AS buildingName, fa.id AS factoryId, fa.name AS factoryName " +
                "FROM areas a JOIN floors f ON a.floorId = f.id JOIN buildings b ON f.buildingId = b.id " +
                "JOIN factories fa ON b.factoryId = fa.id"
    }
}

@Dao
interface TemplateQuestionDao {
    @Query("SELECT * FROM template_questions WHERE type = :type ORDER BY position, id")
    fun observeForType(type: CheckpointType): Flow<List<TemplateQuestionEntity>>

    @Query("SELECT * FROM template_questions WHERE type = :type AND active = 1 ORDER BY position, id")
    suspend fun activeForType(type: CheckpointType): List<TemplateQuestionEntity>

    @Query("SELECT * FROM template_questions WHERE type = :type ORDER BY position, id")
    suspend fun allForType(type: CheckpointType): List<TemplateQuestionEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM template_questions WHERE type = :type")
    suspend fun maxPosition(type: CheckpointType): Int

    @Insert suspend fun insert(question: TemplateQuestionEntity): Long

    @Update suspend fun update(question: TemplateQuestionEntity)

    @Update suspend fun updateAll(questions: List<TemplateQuestionEntity>)
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints ORDER BY name COLLATE NOCASE, code")
    fun observeAll(): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints ORDER BY name COLLATE NOCASE, code")
    suspend fun getAll(): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE id = :id")
    suspend fun getById(id: Long): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(checkpoint: CheckpointEntity): Long

    @Update
    suspend fun update(checkpoint: CheckpointEntity)
}

@Dao
interface InspectionDao {
    /** Drafts first, then completed inspections; newest first within each group. */
    @Transaction
    @Query("SELECT * FROM inspections ORDER BY (completedAt IS NOT NULL), startedAt DESC")
    fun observeAll(): Flow<List<InspectionWithDetails>>

    @Transaction
    @Query("SELECT * FROM inspections WHERE id = :id")
    fun observe(id: Long): Flow<InspectionWithDetails?>

    @Transaction
    @Query("SELECT * FROM inspections WHERE id = :id")
    suspend fun get(id: Long): InspectionWithDetails?

    @Query(
        "SELECT * FROM inspections WHERE checkpointId = :checkpointId AND completedAt IS NULL " +
            "ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun findOpenDraft(checkpointId: Long): InspectionEntity?

    @Insert suspend fun insertInspection(inspection: InspectionEntity): Long

    @Insert suspend fun insertItems(items: List<ChecklistItemEntity>)

    @Query("UPDATE checklist_items SET result = :result WHERE id = :itemId")
    suspend fun setItemResult(itemId: Long, result: ItemResult)

    @Query("UPDATE checklist_items SET note = :note WHERE id = :itemId")
    suspend fun setItemNote(itemId: Long, note: String)

    @Query("UPDATE checklist_items SET priority = :priority, dueAt = :dueAt WHERE id = :itemId")
    suspend fun setFinding(itemId: Long, priority: Priority?, dueAt: Long?)

    @Query("UPDATE inspections SET generalNotes = :notes WHERE id = :id")
    suspend fun setGeneralNotes(id: Long, notes: String)

    @Query(
        "UPDATE inspections SET completedAt = :completedAt, reportFileName = :reportFileName, updatedAt = :completedAt " +
            "WHERE id = :id AND completedAt IS NULL",
    )
    suspend fun markCompleted(id: Long, completedAt: Long, reportFileName: String): Int

    @Query("UPDATE inspections SET reportFileName = :reportFileName WHERE id = :id")
    suspend fun setReportFile(id: Long, reportFileName: String)

    @Query("DELETE FROM inspections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert suspend fun insertPhoto(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getPhoto(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE itemId = :itemId ORDER BY takenAt")
    fun observePhotosForItem(itemId: Long): Flow<List<PhotoEntity>>

    @Delete suspend fun deletePhoto(photo: PhotoEntity)

    // --- sync -----------------------------------------------------------------------------------

    @Transaction
    @Query(
        "SELECT * FROM inspections WHERE completedAt IS NOT NULL AND (syncedAt IS NULL OR syncedAt < updatedAt) " +
            "ORDER BY completedAt LIMIT :limit",
    )
    suspend fun pendingSync(limit: Int): List<InspectionWithDetails>

    @Query("SELECT COUNT(*) FROM inspections WHERE completedAt IS NOT NULL AND (syncedAt IS NULL OR syncedAt < updatedAt)")
    fun observePendingSyncCount(): Flow<Int>

    @Query("UPDATE inspections SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, at: Long)
}

@Dao
interface CorrectiveActionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(actions: List<CorrectiveActionEntity>): List<Long>

    @Query("SELECT * FROM corrective_actions WHERE id = :id")
    suspend fun get(id: Long): CorrectiveActionEntity?

    @Query("SELECT COUNT(*) FROM corrective_actions WHERE inspectionId = :inspectionId")
    suspend fun countForInspection(inspectionId: Long): Int

    @Query("SELECT COUNT(*) FROM corrective_actions")
    suspend fun countAll(): Int

    @Query(
        "UPDATE corrective_actions SET status = 'RESOLVED', resolutionNote = :note, resolvedByName = :by, " +
            "resolvedAt = :at, updatedAt = :at WHERE id = :id AND status = 'OPEN'",
    )
    suspend fun resolve(id: Long, note: String, by: String, at: Long): Int

    @Query(
        "UPDATE corrective_actions SET status = 'VERIFIED', verifiedByName = :by, verifiedAt = :at, updatedAt = :at " +
            "WHERE id = :id AND status = 'RESOLVED'",
    )
    suspend fun verify(id: Long, by: String, at: Long): Int

    @Query(
        "UPDATE corrective_actions SET status = 'OPEN', adminComment = :comment, resolvedAt = NULL, updatedAt = :at " +
            "WHERE id = :id AND status = 'RESOLVED'",
    )
    suspend fun reopen(id: Long, comment: String, at: Long): Int

    @Query(
        "UPDATE corrective_actions SET priority = :priority, dueAt = :dueAt, updatedAt = :at " +
            "WHERE id = :id AND status != 'VERIFIED'",
    )
    suspend fun reschedule(id: Long, priority: Priority, dueAt: Long, at: Long): Int

    @Query("$ROW_SELECT ORDER BY ca.dueAt, ca.id")
    fun observeRows(): Flow<List<ActionRow>>

    @Query("$ROW_SELECT WHERE ca.id = :id")
    fun observeRow(id: Long): Flow<ActionRow?>

    @Query("$ROW_SELECT WHERE ca.syncedAt IS NULL OR ca.syncedAt < ca.updatedAt ORDER BY ca.id LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<ActionRow>

    @Query("SELECT COUNT(*) FROM corrective_actions WHERE syncedAt IS NULL OR syncedAt < updatedAt")
    fun observePendingSyncCount(): Flow<Int>

    @Query("UPDATE corrective_actions SET syncedAt = :at WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, at: Long)

    companion object {
        const val ROW_SELECT =
            "SELECT ca.*, ci.question AS question, ci.section AS section, ci.position AS itemPosition, " +
                "c.id AS checkpointId, c.name AS checkpointName, c.code AS checkpointCode, c.areaId AS checkpointAreaId, " +
                "c.area AS spot, i.siteName AS locationSnapshot, i.inspectorName AS inspectorName, " +
                "i.completedAt AS inspectionCompletedAt " +
                "FROM corrective_actions ca JOIN checklist_items ci ON ci.id = ca.itemId " +
                "JOIN inspections i ON i.id = ca.inspectionId JOIN checkpoints c ON c.id = i.checkpointId"
    }
}
