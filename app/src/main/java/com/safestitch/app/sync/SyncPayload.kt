package com.safestitch.app.sync

import com.safestitch.app.data.ActionSyncBundle
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Sync contract, schema 2 (see scripts/sync-test-server.py for the reference server).
 *
 *  POST <server>/api/v1/sync            JSON SyncRequest, header "Authorization: Bearer <API key>".
 *       -> 200 SyncResponse with one result per record: created | unchanged | updated | conflict.
 *  PUT  <server>/api/v1/photos/<device_id>/<photo_local_id>   raw JPEG, header X-SafeStitch-Sha256.
 *       -> 201 created, 200 unchanged (same bytes already stored), 409 conflict (different bytes).
 *
 * Records are identified by (device_id, type, local_id); "revision" is the record's last-change time, so a
 * retried upload is recognised as a duplicate and an older copy never overwrites a newer one (reported as a
 * conflict instead). Users, passwords and password hashes are never part of the payload.
 */

@Serializable
data class SyncRequest(
    val schema: Int = 2,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("sent_at") val sentAt: String,
    val inspections: List<InspectionDto>,
    @SerialName("corrective_actions") val correctiveActions: List<ActionDto>,
)

@Serializable
data class LocationDto(val factory: String, val building: String, val floor: String, val area: String)

@Serializable
data class PhotoMetaDto(
    @SerialName("local_id") val localId: Long,
    val kind: String,
    @SerialName("item_position") val itemPosition: Int?,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("taken_at") val takenAt: String,
)

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
    val responsible: String?,
)

@Serializable
data class InspectionDto(
    @SerialName("local_id") val localId: Long,
    val revision: Long,
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
    @SerialName("report_file") val reportFile: String?,
    val items: List<ItemDto>,
    val photos: List<PhotoMetaDto>,
)

@Serializable
data class EventDto(val from: String?, val to: String, val by: String, val at: String, val note: String)

@Serializable
data class ActionDto(
    @SerialName("local_id") val localId: Long,
    val revision: Long,
    @SerialName("inspection_local_id") val inspectionLocalId: Long,
    @SerialName("equipment_code") val equipmentCode: String,
    @SerialName("item_position") val itemPosition: Int,
    val question: String,
    val description: String,
    val priority: String,
    @SerialName("due_at") val dueAt: String,
    val status: String,
    val responsible: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("started_by") val startedBy: String?,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("resolution_note") val resolutionNote: String?,
    @SerialName("resolved_by") val resolvedBy: String?,
    @SerialName("resolved_at") val resolvedAt: String?,
    @SerialName("verified_by") val verifiedBy: String?,
    @SerialName("verified_at") val verifiedAt: String?,
    @SerialName("admin_comment") val adminComment: String?,
    val history: List<EventDto>,
    val photos: List<PhotoMetaDto>,
)

@Serializable
data class SyncResultDto(
    val type: String,
    @SerialName("local_id") val localId: Long,
    val status: String,
    val message: String? = null,
)

@Serializable
data class SyncResponse(val results: List<SyncResultDto> = emptyList())

/** SHA-256 and size of a photo file, computed before upload. */
data class PhotoFacts(val sha256: String, val sizeBytes: Long)

object SyncPayload {
    const val TYPE_INSPECTION = "inspection"
    const val TYPE_ACTION = "corrective_action"
    const val STATUS_CONFLICT = "conflict"

    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /** ISO-8601 UTC, whole seconds, e.g. 2026-11-09T13:26:00Z. */
    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun photoMeta(photo: PhotoEntity, itemPosition: Int?, facts: PhotoFacts?) = PhotoMetaDto(
        localId = photo.id,
        kind = if (photo.isAfterFix) "AFTER_FIX" else "EVIDENCE",
        itemPosition = itemPosition,
        sha256 = facts?.sha256.orEmpty(),
        sizeBytes = facts?.sizeBytes ?: 0,
        takenAt = iso(photo.takenAt),
    )

    fun inspection(details: InspectionWithDetails, path: AreaPath?, facts: Map<Long, PhotoFacts>): InspectionDto {
        val inspection = details.inspection
        val summary = details.summary()
        val itemsById = details.items.associateBy { it.id }
        return InspectionDto(
            localId = inspection.id,
            revision = inspection.updatedAt,
            equipmentCode = details.equipmentCode,
            equipmentName = details.equipmentName,
            equipmentType = details.equipmentType.name,
            location = path?.let { LocationDto(it.factoryName, it.buildingName, it.floorName, it.areaName) },
            locationLabel = inspection.siteName,
            exactSpot = details.checkpoint.area,
            inspector = inspection.inspectorName,
            startedAt = iso(inspection.startedAt),
            completedAt = inspection.completedAt?.let(::iso),
            result = summary.outcome.name,
            scorePercent = summary.scorePercent,
            generalNotes = inspection.generalNotes,
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
                    responsible = it.responsibleName,
                )
            },
            photos = details.sortedPhotos.filterNot { it.isAfterFix }.map { photo ->
                photoMeta(photo, photo.itemId?.let { itemsById[it]?.position?.plus(1) }, facts[photo.id])
            },
        )
    }

    fun action(bundle: ActionSyncBundle, facts: Map<Long, PhotoFacts>): ActionDto {
        val row = bundle.row
        val a = row.action
        return ActionDto(
            localId = a.id,
            revision = a.updatedAt,
            inspectionLocalId = a.inspectionId,
            equipmentCode = row.checkpointCode,
            itemPosition = row.itemPosition + 1,
            question = row.question,
            description = a.description,
            priority = a.priority.name,
            dueAt = iso(a.dueAt),
            status = a.status.name,
            responsible = a.responsibleName,
            createdBy = a.createdByName,
            createdAt = iso(a.createdAt),
            startedBy = a.startedByName,
            startedAt = a.startedAt?.let(::iso),
            resolutionNote = a.resolutionNote,
            resolvedBy = a.resolvedByName,
            resolvedAt = a.resolvedAt?.let(::iso),
            verifiedBy = a.verifiedByName,
            verifiedAt = a.verifiedAt?.let(::iso),
            adminComment = a.adminComment,
            history = bundle.events.sortedWith(compareBy({ it.at }, { it.id })).map {
                EventDto(from = it.fromStatus?.name, to = it.toStatus.name, by = it.byName, at = iso(it.at), note = it.note)
            },
            photos = bundle.photos.map { photoMeta(it, row.itemPosition + 1, facts[it.id]) },
        )
    }

    fun encode(request: SyncRequest): String = json.encodeToString(SyncRequest.serializer(), request)

    /** Unknown or empty bodies mean "no per-record results": every record counts as accepted. */
    fun decodeResponse(body: String): SyncResponse = try {
        if (body.isBlank()) SyncResponse() else json.decodeFromString(SyncResponse.serializer(), body)
    } catch (_: SerializationException) {
        SyncResponse()
    } catch (_: IllegalArgumentException) {
        SyncResponse()
    }
}

object SyncUrls {
    private val localHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** Returns a problem description, or null if the address can be used. Plain http is only for local testing in debug builds. */
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

    fun base(raw: String): String = raw.trim().trimEnd('/')
    fun recordsEndpoint(raw: String): String = base(raw) + "/api/v1/sync"
    fun photoEndpoint(raw: String, deviceId: String, photoId: Long): String = base(raw) + "/api/v1/photos/$deviceId/$photoId"
}
