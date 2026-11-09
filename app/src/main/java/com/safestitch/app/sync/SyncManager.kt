package com.safestitch.app.sync

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.safestitch.app.BuildConfig
import com.safestitch.app.SafeStitchApp
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncConfig(val enabled: Boolean = false, val serverUrl: String = "", val apiKey: String = "")

data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val lastSummary: String? = null,
)

enum class SyncPhase(val label: String) {
    OFF("Sync is off"),
    IDLE("Up to date"),
    WAITING("Waiting for a network connection"),
    RUNNING("Syncing…"),
}

enum class SyncOutcome { SUCCESS, RETRY, FAILED, DISABLED }

/**
 * Optional offline-to-online sync. Off by default; an admin turns it on with a server address and API key.
 * Every submitted inspection and corrective-action change is queued; WorkManager uploads the queue as soon
 * as the phone is online (it waits automatically while offline) and retries with back-off on errors.
 */
class SyncManager(
    private val context: Context,
    private val repository: InspectionRepository,
    private val catalog: CatalogRepository,
    private val client: SyncClient = SyncClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("safestitch_sync", Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow(readConfig())
    private val mutableStatus = MutableStateFlow(readStatus())
    private val mutex = Mutex()
    private val workManager get() = WorkManager.getInstance(context)

    val config: StateFlow<SyncConfig> = mutableConfig.asStateFlow()
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()
    val pendingCount: Flow<Int> get() = repository.observePendingSyncCount()

    val phase: Flow<SyncPhase>
        get() = combine(config, workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)) { cfg, infos ->
            when {
                !cfg.enabled -> SyncPhase.OFF
                infos.any { it.state == WorkInfo.State.RUNNING } -> SyncPhase.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> SyncPhase.WAITING
                else -> SyncPhase.IDLE
            }
        }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY_DEVICE, it) } }

    /** Saves the settings. Returns a problem description, or null on success. */
    fun save(newConfig: SyncConfig): String? {
        val clean = newConfig.copy(serverUrl = newConfig.serverUrl.trim(), apiKey = newConfig.apiKey.trim())
        if (clean.enabled) {
            SyncUrls.problem(clean.serverUrl, allowLocalHttp = BuildConfig.DEBUG)?.let { return it }
            if (clean.apiKey.isEmpty()) return "Enter the API key from your sync server."
        }
        prefs.edit {
            putBoolean(KEY_ENABLED, clean.enabled)
            putString(KEY_URL, clean.serverUrl)
            putString(KEY_API_KEY, clean.apiKey)
        }
        mutableConfig.value = clean
        if (clean.enabled) requestSync() else workManager.cancelUniqueWork(WORK_NAME)
        return null
    }

    /** Queues an upload. It runs as soon as there is a network connection. */
    fun requestSync() {
        if (!config.value.enabled) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Uploads everything that changed since the last successful sync. Called by [SyncWorker]. */
    suspend fun runSync(): SyncOutcome = mutex.withLock {
        val cfg = config.value
        if (!cfg.enabled) return SyncOutcome.DISABLED
        val endpoint = SyncUrls.endpoint(cfg.serverUrl)
        val attemptAt = clock()
        var sentInspections = 0
        var sentActions = 0
        try {
            repeat(MAX_BATCHES) {
                val snapshot = clock()
                val inspections = repository.pendingSyncInspections(INSPECTION_BATCH)
                val actions = repository.pendingSyncActions(ACTION_BATCH)
                if (inspections.isEmpty() && actions.isEmpty()) {
                    recordSuccess(attemptAt, sentInspections, sentActions)
                    return SyncOutcome.SUCCESS
                }
                val paths = catalog.getAreaPaths().associateBy { it.areaId }
                val request = SyncRequest(
                    deviceId = deviceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    sentAt = SyncPayload.iso(snapshot),
                    inspections = inspections.map { SyncPayload.inspection(it, it.checkpoint.areaId?.let(paths::get)) },
                    correctiveActions = actions.map(SyncPayload::action),
                )
                val code = withContext(Dispatchers.IO) { client.post(endpoint, cfg.apiKey, SyncPayload.encode(request)) }
                when (code) {
                    in 200..299 -> {
                        repository.markSynced(inspections.map { it.inspection.id }, actions.map { it.action.id }, snapshot)
                        sentInspections += inspections.size
                        sentActions += actions.size
                    }
                    401, 403 -> {
                        recordError(attemptAt, "The server rejected the API key (HTTP $code). Check it under Admin › Sync.")
                        return SyncOutcome.FAILED
                    }
                    in 400..499 -> {
                        recordError(attemptAt, "The server refused the data (HTTP $code).")
                        return SyncOutcome.FAILED
                    }
                    else -> {
                        recordError(attemptAt, "Server error (HTTP $code). Will retry automatically.")
                        return SyncOutcome.RETRY
                    }
                }
            }
            recordSuccess(attemptAt, sentInspections, sentActions)
            SyncOutcome.SUCCESS
        } catch (e: IOException) {
            recordError(attemptAt, "Could not reach the server (${e.javaClass.simpleName}). Will retry when online.")
            SyncOutcome.RETRY
        }
    }

    private fun recordSuccess(at: Long, inspections: Int, actions: Int) {
        val summary = "Sent $inspections inspection(s) and $actions corrective-action update(s)."
        prefs.edit {
            putLong(KEY_LAST_SUCCESS, clock())
            putLong(KEY_LAST_ATTEMPT, at)
            remove(KEY_LAST_ERROR)
            putString(KEY_LAST_SUMMARY, summary)
        }
        mutableStatus.value = readStatus()
    }

    private fun recordError(at: Long, message: String) {
        prefs.edit {
            putLong(KEY_LAST_ATTEMPT, at)
            putString(KEY_LAST_ERROR, message)
        }
        mutableStatus.value = readStatus()
    }

    private fun readConfig() = SyncConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        serverUrl = prefs.getString(KEY_URL, null).orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
    )

    private fun readStatus() = SyncStatus(
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, -1L).takeIf { it > 0 },
        lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT, -1L).takeIf { it > 0 },
        lastError = prefs.getString(KEY_LAST_ERROR, null),
        lastSummary = prefs.getString(KEY_LAST_SUMMARY, null),
    )

    companion object {
        const val WORK_NAME = "safestitch-sync"
        private const val MAX_BATCHES = 50
        private const val INSPECTION_BATCH = 20
        private const val ACTION_BATCH = 200
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SUMMARY = "last_summary"
    }
}

/** Plain HttpURLConnection client: no extra network library. Returns the HTTP status code. */
class SyncClient {
    fun post(url: String, apiKey: String, body: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "SafeStitch/${BuildConfig.VERSION_NAME}")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            return code
        } finally {
            connection.disconnect()
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when ((applicationContext as SafeStitchApp).container.syncManager.runSync()) {
            SyncOutcome.SUCCESS, SyncOutcome.DISABLED -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.FAILED -> Result.failure()
        }
}
