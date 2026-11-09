package com.safestitch.app

import android.content.Context
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.SettingsRepository
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.pdf.ReportService

/** Manual dependency container: one instance per process, owned by [SafeStitchApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val photoStorage = PhotoStorage(appContext)
    val settings = SettingsRepository(appContext)
    val database: SafeStitchDatabase by lazy { SafeStitchDatabase.create(appContext) }
    val repository: InspectionRepository by lazy { InspectionRepository(database, photoStorage) }
    val reportService: ReportService by lazy { ReportService(appContext, repository, photoStorage) }
}
