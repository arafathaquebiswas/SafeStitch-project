package com.safestitch.app.sync

import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.InspectionWithDetails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Sync contract (version 1). The app POSTs JSON to <server>/api/v1/sync with
 * "Authorization: Bearer <API key>". Any 2xx response marks the records as synced.
 * Records are identified by (device_id, local_id). Passwords, photos and PDFs never leave the phone.
 */

@Serializable
data class SyncRequest(
    val schema: Int = 1,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("sent_at") val sentAt: String,
    val inspections: List<InspectionDto>,
    @SerialName("corrective_actions") val correctiveActions: List<ActionDto>,
)

@Serializable
data class LocationDto(val factory: String, val building: String, val floor: String, val area: String)

@Serializable
data class ItemDto(
    val position: Int,
    val section: String,
    val question: String,
    val critical: Boolean,
    val result: String,
    val note: String,
    val priority: String?,
    @SerialName("due_at") val dueAt: String?,
)

@Serializable
data class InspectionDto(
    @SerialName("local_id") val localId: Long,
    @SerialName("equipment_code") val equipmentCode: String,
    @SerialName("equipment_name") val equipmentName: String,
    @SerialName("equipment_type") val equipmentType: String,
    val location: LocationDto?,
    @SerialName("location_label") val locationLabel: String,
    @SerialName("exact_spot") val exactSpot: String,
    val inspector: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String?,
    val result: String,
    @SerialName("score_percent") val scorePercent: Int,
    @SerialName("general_notes") val generalNotes: String,
    @SerialName("photo_count") val photoCount: Int,
    @SerialName("report_file") val reportFile: String?,
    val items: List<ItemDto>,
)

@Serializable
data class ActionDto(
    @SerialName("local_id") val localId: Long,
    @SerialName("inspection_local_id") val inspectionLocalId: Long,
    @SerialName("equipment_code") val equipmentCode: String,
    @SerialName("item_position") val itemPosition: Int,
    val question: String,
    val description: String,
    val priority: String,
    @SerialName("due_at") val dueAt: String,
    val status: String,
    @SerialName("resolution_note") val resolutionNote: String?,
    @SerialName("resolved_by") val resolvedBy: String?,
    @SerialName("resolved_at") val resolvedAt: String?,
    @SerialName("verified_by") val verifiedBy: String?,
    @SerialName("verified_at") val verifiedAt: String?,
    @SerialName("admin_comment") val adminComment: String?,
)

object SyncPayload {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** ISO-8601 UTC, whole seconds, e.g. 2026-11-09T13:26:00Z. */
    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    fun inspection(details: InspectionWithDetails, path: AreaPath?): InspectionDto {
        val inspection = details.inspection
        val summary = details.summary()
        return InspectionDto(
            localId = inspection.id,
            equipmentCode = details.checkpoint.code,
            equipmentName = details.checkpoint.name,
            equipmentType = details.checkpoint.type.name,
            location = path?.let { LocationDto(it.factoryName, it.buildingName, it.floorName, it.areaName) },
            locationLabel = inspection.siteName,
            exactSpot = details.checkpoint.area,
            inspector = inspection.inspectorName,
            startedAt = iso(inspection.startedAt),
            completedAt = inspection.completedAt?.let(::iso),
            result = summary.outcome.name,
            scorePercent = summary.scorePercent,
            generalNotes = inspection.generalNotes,
            photoCount = details.photos.size,
            reportFile = inspection.reportFileName,
            items = details.sortedItems.map {
                ItemDto(
                    position = it.position + 1,
                    section = it.section,
                    question = it.question,
                    critical = it.critical,
                    result = it.result.name,
                    note = it.note,
                    priority = it.priority?.name,
                    dueAt = it.dueAt?.let(::iso),
                )
            },
        )
    }

    fun action(row: ActionRow): ActionDto {
        val a = row.action
        return ActionDto(
            localId = a.id,
            inspectionLocalId = a.inspectionId,
            equipmentCode = row.checkpointCode,
            itemPosition = row.itemPosition + 1,
            question = row.question,
            description = a.description,
            priority = a.priority.name,
            dueAt = iso(a.dueAt),
            status = a.status.name,
            resolutionNote = a.resolutionNote,
            resolvedBy = a.resolvedByName,
            resolvedAt = a.resolvedAt?.let(::iso),
            verifiedBy = a.verifiedByName,
            verifiedAt = a.verifiedAt?.let(::iso),
            adminComment = a.adminComment,
        )
    }

    fun encode(request: SyncRequest): String = json.encodeToString(SyncRequest.serializer(), request)
}

object SyncUrls {
    private val localHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** Returns a problem description, or null if the address can be used. Plain http is only for local testing. */
    fun problem(raw: String, allowLocalHttp: Boolean): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val host = uri?.host?.lowercase(Locale.ROOT)
        return when {
            uri == null || host.isNullOrEmpty() || scheme == null -> "Enter the server address, e.g. https://sync.example.com"
            scheme == "https" -> null
            scheme == "http" && allowLocalHttp && host in localHosts -> null
            else -> "Use a secure https:// address."
        }
    }

    fun endpoint(raw: String): String = raw.trim().trimEnd('/') + "/api/v1/sync"
}
