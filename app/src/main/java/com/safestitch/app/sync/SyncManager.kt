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
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.SyncConflict
import com.safestitch.app.data.SyncKind
import com.safestitch.app.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
 * Submitted inspections, corrective-action changes and their photos are queued in the database (so the queue
 * survives restarts); WorkManager uploads it as soon as the phone is online and retries with back-off.
 * Uploads are idempotent: a retried request never creates duplicates on the server.
 */
class SyncManager(
    private val context: Context,
    private val repository: InspectionRepository,
    private val catalog: CatalogRepository,
    private val photoStorage: PhotoStorage,
    private val client: SyncClient = SyncClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("safestitch_sync", Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow(readConfig())
    private val mutableStatus = MutableStateFlow(readStatus())
    private val mutex = Mutex()
    private val workManagerOrNull: WorkManager?
        get() = runCatching { WorkManager.getInstance(context) }.getOrNull()

    val config: StateFlow<SyncConfig> = mutableConfig.asStateFlow()
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()
    val pendingCount: Flow<Int> get() = repository.observePendingSyncCount()
    val pendingPhotoCount: Flow<Int> get() = repository.observePendingPhotoCount()
    val conflicts: Flow<List<SyncConflict>> get() = repository.observeConflicts()

    val phase: Flow<SyncPhase>
        get() {
            val workInfosFlow = workManagerOrNull?.getWorkInfosForUniqueWorkFlow(WORK_NAME) ?: flowOf(emptyList())
            return combine(config, workInfosFlow) { cfg, infos ->
                when {
                    !cfg.enabled -> SyncPhase.OFF
                    infos.any { it.state == WorkInfo.State.RUNNING } -> SyncPhase.RUNNING
                    infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> SyncPhase.WAITING
                    else -> SyncPhase.IDLE
                }
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
        if (clean.enabled) requestSync() else workManagerOrNull?.cancelUniqueWork(WORK_NAME)
        return null
    }

    /** Queues an upload after the current one (if any). It runs as soon as there is a network connection. */
    fun requestSync() = enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** "Sync now": replaces any upload waiting for its retry back-off. */
    fun syncNow() = enqueue(ExistingWorkPolicy.REPLACE)

    private fun enqueue(policy: ExistingWorkPolicy) {
        if (!config.value.enabled) return
        val wm = workManagerOrNull ?: return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, policy, request)
    }

    /** Uploads everything that changed since the last successful sync, then the photos. Called by [SyncWorker]. */
    suspend fun runSync(): SyncOutcome = mutex.withLock {
        val cfg = config.value
        if (!cfg.enabled) return SyncOutcome.DISABLED
        val attemptAt = clock()
        val counts = IntArray(4) // inspections, actions, photos, conflicts
        try {
            // 1. Records (inspections + corrective actions), in batches.
            for (batch in 0 until MAX_BATCHES) {
                val snapshot = clock()
                val inspections = repository.pendingSyncInspections(INSPECTION_BATCH)
                val actions = repository.pendingSyncActions(ACTION_BATCH)
                if (inspections.isEmpty() && actions.isEmpty()) break
                val photos = inspections.flatMap { it.photos.filterNot(PhotoEntity::isAfterFix) } + actions.flatMap { it.photos }
                val facts = photos.associate { it.id to photoFacts(it) }.filterValues { it != null }.mapValues { it.value!! }
                val paths = catalog.getAreaPaths().associateBy { it.areaId }
                val request = SyncRequest(
                    deviceId = deviceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    sentAt = SyncPayload.iso(snapshot),
                    inspections = inspections.map { SyncPayload.inspection(it, it.checkpoint.areaId?.let(paths::get), facts) },
                    correctiveActions = actions.map { SyncPayload.action(it, facts) },
                )
                val response = withContext(Dispatchers.IO) { client.postJson(SyncUrls.recordsEndpoint(cfg.serverUrl), cfg.apiKey, SyncPayload.encode(request)) }
                if (response.code !in 200..299) return fail(attemptAt, response.code)
                val results = SyncPayload.decodeResponse(response.body).results
                fun conflictFor(type: String, id: Long) = results.firstOrNull { it.type == type && it.localId == id && it.status == SyncPayload.STATUS_CONFLICT }
                val okInspections = mutableListOf<Long>()
                inspections.forEach { d ->
                    val conflict = conflictFor(SyncPayload.TYPE_INSPECTION, d.inspection.id)
                    if (conflict != null) {
                        repository.setConflict(SyncKind.INSPECTION, d.inspection.id, conflict.message ?: "The server has a different copy of this inspection.")
                        counts[3]++
                    } else {
                        okInspections += d.inspection.id
                    }
                }
                val okActions = mutableListOf<Long>()
                actions.forEach { b ->
                    val conflict = conflictFor(SyncPayload.TYPE_ACTION, b.row.action.id)
                    if (conflict != null) {
                        repository.setConflict(SyncKind.CORRECTIVE_ACTION, b.row.action.id, conflict.message ?: "The server has a newer copy of this action.")
                        counts[3]++
                    } else {
                        okActions += b.row.action.id
                    }
                }
                repository.markSynced(okInspections, okActions, snapshot)
                counts[0] += okInspections.size
                counts[1] += okActions.size
            }
            // 2. Photos of records that are now on the server.
            for (batch in 0 until MAX_BATCHES) {
                val photos = repository.pendingPhotos(PHOTO_BATCH)
                if (photos.isEmpty()) break
                for (photo in photos) {
                    val file = photoStorage.file(photo.fileName)
                    val facts = photoFacts(photo)
                    if (facts == null) {
                        repository.setConflict(SyncKind.PHOTO, photo.id, "The photo file is missing on this phone.")
                        counts[3]++
                        continue
                    }
                    val response = withContext(Dispatchers.IO) {
                        client.putPhoto(SyncUrls.photoEndpoint(cfg.serverUrl, deviceId, photo.id), cfg.apiKey, file, facts.sha256, photo)
                    }
                    when (response.code) {
                        in 200..299 -> {
                            repository.markPhotoSynced(photo.id, clock())
                            counts[2]++
                        }
                        409 -> {
                            repository.setConflict(SyncKind.PHOTO, photo.id, "Server already has a different photo ${photo.id} for this phone.")
                            counts[3]++
                        }
                        else -> return fail(attemptAt, response.code)
                    }
                }
            }
            recordSuccess(attemptAt, counts)
            SyncOutcome.SUCCESS
        } catch (e: IOException) {
            recordError(attemptAt, "Could not reach the server (${e.javaClass.simpleName}). Will retry when online.")
            SyncOutcome.RETRY
        }
    }

    /** SHA-256 and size of the photo file; cached in the database after the first computation. */
    private suspend fun photoFacts(photo: PhotoEntity): PhotoFacts? = withContext(Dispatchers.IO) {
        val file = runCatching { photoStorage.file(photo.fileName) }.getOrNull()?.takeIf(File::isFile) ?: return@withContext null
        val sha = photo.sha256 ?: sha256(file).also { repository.setPhotoSha(photo.id, it) }
        PhotoFacts(sha, file.length())
    }

    private fun fail(attemptAt: Long, code: Int): SyncOutcome = when (code) {
        401, 403 -> {
            recordError(attemptAt, "The server rejected the API key (HTTP $code). Check it under Admin › Sync.")
            SyncOutcome.FAILED
        }
        in 400..499 -> {
            recordError(attemptAt, "The server refused the data (HTTP $code).")
            SyncOutcome.FAILED
        }
        else -> {
            recordError(attemptAt, "Server error (HTTP $code). Will retry automatically.")
            SyncOutcome.RETRY
        }
    }

    private fun recordSuccess(at: Long, counts: IntArray) {
        val summary = "Sent ${counts[0]} inspection(s), ${counts[1]} corrective-action update(s) and ${counts[2]} photo(s)" +
            if (counts[3] > 0) "; ${counts[3]} conflict(s) need attention." else "."
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
        private const val ACTION_BATCH = 100
        private const val PHOTO_BATCH = 10
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_ATTEMPT = "last_attempt_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SUMMARY = "last_summary"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class HttpResult(val code: Int, val body: String)

/** Plain HttpURLConnection client: no extra network library. */
open class SyncClient {
    open fun postJson(url: String, apiKey: String, body: String): HttpResult =
        send(url, "POST", apiKey, "application/json; charset=utf-8", emptyMap()) { it.write(body.toByteArray(Charsets.UTF_8)) }

    open fun putPhoto(url: String, apiKey: String, file: File, sha256: String, photo: PhotoEntity): HttpResult = send(
        url,
        "PUT",
        apiKey,
        "image/jpeg",
        buildMap {
            put("X-SafeStitch-Sha256", sha256)
            put("X-SafeStitch-Inspection", photo.inspectionId.toString())
            photo.actionId?.let { put("X-SafeStitch-Action", it.toString()) }
            put("X-SafeStitch-Kind", if (photo.isAfterFix) "AFTER_FIX" else "EVIDENCE")
        },
    ) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun send(
        url: String,
        method: String,
        apiKey: String,
        contentType: String,
        headers: Map<String, String>,
        writeBody: (java.io.OutputStream) -> Unit,
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "SafeStitch/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.outputStream.use(writeBody)
            val code = connection.responseCode
            val body = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResult(code, body)
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
