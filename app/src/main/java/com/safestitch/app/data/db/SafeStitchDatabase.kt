package com.safestitch.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * On-device database.
 *  - v1: checkpoints, inspections, checklist items, photos.
 *  - v2: users, factory/building/floor/area, editable questions, finding priority/due date,
 *        corrective actions, sync bookkeeping, and captured camera inspections with status tags.
 *        Existing data is converted by [Migration1To2].
 * Bump [version] and add a migration for every future entity change; schemas are exported to app/schemas.
 */
@Database(
    entities = [
        CheckpointEntity::class, InspectionEntity::class, ChecklistItemEntity::class, PhotoEntity::class,
        UserEntity::class, FactoryEntity::class, BuildingEntity::class, FloorEntity::class, AreaEntity::class,
        TemplateQuestionEntity::class, CorrectiveActionEntity::class, ActionEventEntity::class,
        Inspection::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = Migration1To2::class)],
)
@TypeConverters(StatusTagConverters::class)
abstract class SafeStitchDatabase : RoomDatabase() {
    abstract fun checkpointDao(): CheckpointDao
    abstract fun inspectionDao(): InspectionDao
    abstract fun userDao(): UserDao
    abstract fun locationDao(): LocationDao
    abstract fun templateQuestionDao(): TemplateQuestionDao
    abstract fun correctiveActionDao(): CorrectiveActionDao

    /** Fresh installs start with the built-in checklists and sample captured inspection photos. */
    private object SeedOnCreate : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            DatabaseSeed.seedQuestions(db)
            DatabaseSeed.seedSampleCapturedInspections(db)
        }
    }

    private object SeedQuestionsOnly : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = DatabaseSeed.seedQuestions(db)
    }

    companion object {
        const val FILE_NAME = "safestitch.db"

        fun create(context: Context): SafeStitchDatabase =
            Room.databaseBuilder(context.applicationContext, SafeStitchDatabase::class.java, FILE_NAME)
                .addCallback(SeedOnCreate)
                .build()

        fun inMemory(context: Context): SafeStitchDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SafeStitchDatabase::class.java)
                .addCallback(SeedQuestionsOnly)
                .build()
    }
}
