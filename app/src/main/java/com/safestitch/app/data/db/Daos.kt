package com.safestitch.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.safestitch.app.domain.ItemResult
import kotlinx.coroutines.flow.Flow

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

    @Insert
    suspend fun insertInspection(inspection: InspectionEntity): Long

    @Insert
    suspend fun insertItems(items: List<ChecklistItemEntity>)

    @Query("UPDATE checklist_items SET result = :result WHERE id = :itemId")
    suspend fun setItemResult(itemId: Long, result: ItemResult)

    @Query("UPDATE checklist_items SET note = :note WHERE id = :itemId")
    suspend fun setItemNote(itemId: Long, note: String)

    @Query("UPDATE inspections SET inspectorName = :inspectorName, siteName = :siteName WHERE id = :id")
    suspend fun updateHeader(id: Long, inspectorName: String, siteName: String)

    @Query("UPDATE inspections SET generalNotes = :notes WHERE id = :id")
    suspend fun setGeneralNotes(id: Long, notes: String)

    @Query("UPDATE inspections SET completedAt = :completedAt, reportFileName = :reportFileName WHERE id = :id")
    suspend fun markCompleted(id: Long, completedAt: Long, reportFileName: String)

    @Query("UPDATE inspections SET reportFileName = :reportFileName WHERE id = :id")
    suspend fun setReportFile(id: Long, reportFileName: String)

    @Query("DELETE FROM inspections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getPhoto(id: Long): PhotoEntity?

    @Delete
    suspend fun deletePhoto(photo: PhotoEntity)
}
