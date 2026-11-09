package com.safestitch.app

import android.app.Application

class SafeStitchApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // If sync is enabled, upload anything still queued from earlier (waits for a network).
        container.syncManager.requestSync()
    }
}
