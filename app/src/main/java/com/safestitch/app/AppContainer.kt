package com.safestitch.app

import android.content.Context
import com.safestitch.app.data.AuthRepository
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container: one instance per process, owned by [SafeStitchApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val photoStorage = PhotoStorage(appContext)
    val database: SafeStitchDatabase by lazy { SafeStitchDatabase.create(appContext) }
    val auth: AuthRepository by lazy { AuthRepository(appContext, database, appScope) }
    val catalog: CatalogRepository by lazy { CatalogRepository(database) }
    val repository: InspectionRepository by lazy {
        InspectionRepository(database, photoStorage).also { repo -> repo.onSyncableChange = { syncManager.requestSync() } }
    }
    val reportService: ReportService by lazy { ReportService(appContext, repository, photoStorage) }
    val syncManager: SyncManager by lazy { SyncManager(appContext, repository, catalog) }
}
