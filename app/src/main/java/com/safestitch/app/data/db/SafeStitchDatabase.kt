package com.safestitch.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device database. Bump [version] and add a Migration whenever an entity changes,
 * otherwise existing inspections on users' phones would be lost.
 */
@Database(
    entities = [CheckpointEntity::class, InspectionEntity::class, ChecklistItemEntity::class, PhotoEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SafeStitchDatabase : RoomDatabase() {
    abstract fun checkpointDao(): CheckpointDao
    abstract fun inspectionDao(): InspectionDao

    companion object {
        const val FILE_NAME = "safestitch.db"

        fun create(context: Context): SafeStitchDatabase =
            Room.databaseBuilder(context.applicationContext, SafeStitchDatabase::class.java, FILE_NAME).build()

        fun inMemory(context: Context): SafeStitchDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SafeStitchDatabase::class.java).build()
    }
}
