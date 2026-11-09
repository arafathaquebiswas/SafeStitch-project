package com.safestitch.app.sync

import com.safestitch.app.data.ActionSyncBundle
import com.safestitch.app.data.db.ActionEventEntity
import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.CorrectiveActionEntity
import com.safestitch.app.data.db.InspectionEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncPayloadTest {
    private val t0 = Instant.parse("2026-11-09T08:00:00Z").toEpochMilli()
    private val sha = "a".repeat(64)

    private val details = InspectionWithDetails(
        inspection = InspectionEntity(
            id = 7, checkpointId = 3, inspectorName = "Rina Akter", siteName = "Main › B1 › Floor 2 › Cutting",
            startedAt = t0, completedAt = t0 + 600_000, reportFileName = "r.pdf", updatedAt = t0 + 600_000,
            equipmentCode = "FE-12", equipmentName = "Extinguisher 12 (as inspected)", equipmentType = CheckpointType.FIRE_EXTINGUISHER,
        ),
        // The equipment was renamed after the inspection: the payload must use the snapshot.
        checkpoint = CheckpointEntity(id = 3, code = "FE-12", name = "Renamed later", area = "Stair B", type = CheckpointType.FIRE_EXTINGUISHER, createdAt = t0, areaId = 4),
        items = listOf(
            ChecklistItemEntity(id = 2, inspectionId = 7, position = 1, section = "Fire", question = "Q2", critical = false, result = ItemResult.PASS),
            ChecklistItemEntity(
                id = 1, inspectionId = 7, position = 0, section = "Fire", question = "Q1", critical = true,
                result = ItemResult.FAIL, note = "Seal broken", priority = Priority.CRITICAL, dueAt = t0 + 86_400_000, responsibleName = "Karim",
            ),
        ),
        photos = listOf(
            PhotoEntity(id = 1, inspectionId = 7, itemId = 1, fileName = "a.jpg", takenAt = t0),
            PhotoEntity(id = 2, inspectionId = 7, itemId = 1, fileName = "fix.jpg", takenAt = t0 + 5_000_000, actionId = 9),
        ),
        actions = emptyList(),
    )
    private val path = AreaPath(4, "Cutting", 3, "Floor 2", 2, "B1", 1, "Main")
    private val facts = mapOf(1L to PhotoFacts(sha, 1234), 2L to PhotoFacts("b".repeat(64), 999))

    @Test
    fun inspectionPayloadUsesSnapshotsRevisionsAndEvidencePhotos() {
        val dto = SyncPayload.inspection(details, path, facts)
        val request = SyncRequest(deviceId = "dev-1", appVersion = "1.0.0", sentAt = SyncPayload.iso(t0), inspections = listOf(dto), correctiveActions = emptyList())
        val encoded = SyncPayload.encode(request)
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(2, json["schema"]!!.jsonPrimitive.int)
        val insp = json["inspections"]!!.jsonArray[0].jsonObject
        assertEquals(t0 + 600_000, insp["revision"]!!.jsonPrimitive.long)
        assertEquals("Extinguisher 12 (as inspected)", insp["equipment_name"]!!.jsonPrimitive.content)
        assertEquals("FIRE_EXTINGUISHER", insp["equipment_type"]!!.jsonPrimitive.content)
        assertEquals("2026-11-09T08:10:00Z", insp["completed_at"]!!.jsonPrimitive.content)
        assertEquals("FAIL", insp["result"]!!.jsonPrimitive.content) // critical item failed
        val items = insp["items"]!!.jsonArray
        assertEquals("Karim", items[0].jsonObject["responsible"]!!.jsonPrimitive.content)
        val photos = insp["photos"]!!.jsonArray
        assertEquals("only evidence photos belong to the inspection", 1, photos.size)
        assertEquals(sha, photos[0].jsonObject["sha256"]!!.jsonPrimitive.content)
        assertEquals("EVIDENCE", photos[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(1, photos[0].jsonObject["item_position"]!!.jsonPrimitive.int)
    }

    @Test
    fun noAccountDataEverLeavesThePhone() {
        val encoded = SyncPayload.encode(
            SyncRequest(deviceId = "d", appVersion = "1", sentAt = "x", inspections = listOf(SyncPayload.inspection(details, path, facts)), correctiveActions = listOf(SyncPayload.action(bundle(), facts))),
        )
        listOf("password", "pbkdf2", "username", "hash\"").forEach { assertFalse("payload contains $it", encoded.contains(it, ignoreCase = true)) }
    }

    private fun bundle() = ActionSyncBundle(
        row = ActionRow(
            action = CorrectiveActionEntity(
                id = 9, inspectionId = 7, itemId = 1, description = "Seal broken", priority = Priority.CRITICAL,
                dueAt = t0 + 86_400_000, status = ActionStatus.VERIFIED, responsibleName = "Karim", createdByName = "Rina Akter", createdAt = t0,
                startedByName = "Karim", startedAt = t0 + 1_800_000, resolutionNote = "Replaced", resolvedByName = "Karim",
                resolvedAt = t0 + 3_600_000, verifiedByName = "Admin", verifiedAt = t0 + 7_200_000, updatedAt = t0 + 7_200_000,
            ),
            question = "Q1", section = "Fire", itemPosition = 0, checkpointId = 3, checkpointName = "Extinguisher 12",
            checkpointCode = "FE-12", checkpointAreaId = 4, spot = "", locationSnapshot = "", inspectorName = "Rina Akter",
            inspectionCompletedAt = t0,
        ),
        events = listOf(
            ActionEventEntity(id = 2, actionId = 9, fromStatus = ActionStatus.OPEN, toStatus = ActionStatus.IN_PROGRESS, byName = "Karim", at = t0 + 1_800_000),
            ActionEventEntity(id = 1, actionId = 9, fromStatus = null, toStatus = ActionStatus.OPEN, byName = "Rina Akter", at = t0),
            ActionEventEntity(id = 3, actionId = 9, fromStatus = ActionStatus.IN_PROGRESS, toStatus = ActionStatus.RESOLVED, byName = "Karim", at = t0 + 3_600_000),
            ActionEventEntity(id = 4, actionId = 9, fromStatus = ActionStatus.RESOLVED, toStatus = ActionStatus.VERIFIED, byName = "Admin", at = t0 + 7_200_000),
        ),
        photos = listOf(details.photos[1]),
    )

    @Test
    fun actionPayloadCarriesFullHistoryAndAfterFixPhotos() {
        val dto = SyncPayload.action(bundle(), facts)
        assertEquals(9, dto.localId)
        assertEquals(t0 + 7_200_000, dto.revision)
        assertEquals("VERIFIED", dto.status)
        assertEquals("Karim", dto.responsible)
        assertEquals("2026-11-09T08:30:00Z", dto.startedAt)
        assertEquals("2026-11-09T10:00:00Z", dto.verifiedAt)
        assertEquals(listOf(null, "OPEN", "IN_PROGRESS", "RESOLVED"), dto.history.map { it.from })
        assertEquals(listOf("OPEN", "IN_PROGRESS", "RESOLVED", "VERIFIED"), dto.history.map { it.to })
        assertEquals(1, dto.photos.size)
        assertEquals("AFTER_FIX", dto.photos[0].kind)
        assertNull(dto.adminComment)
    }

    @Test
    fun serverResultsAndConflictsAreParsed() {
        val response = SyncPayload.decodeResponse(
            """{"results":[{"type":"inspection","local_id":7,"status":"unchanged"},
               {"type":"corrective_action","local_id":9,"status":"conflict","message":"Server has a newer version","extra":1}],"server":"x"}""",
        )
        assertEquals(2, response.results.size)
        assertEquals("conflict", response.results[1].status)
        assertEquals("Server has a newer version", response.results[1].message)
        assertTrue(SyncPayload.decodeResponse("").results.isEmpty())
        assertTrue(SyncPayload.decodeResponse("<html>oops</html>").results.isEmpty())
    }

    @Test
    fun serverAddressRules() {
        assertNull(SyncUrls.problem("https://sync.example.com", allowLocalHttp = false))
        assertNotNull(SyncUrls.problem("http://sync.example.com", allowLocalHttp = true))
        assertNotNull("release builds never allow http", SyncUrls.problem("http://10.0.2.2:8787", allowLocalHttp = false))
        assertNull(SyncUrls.problem("http://10.0.2.2:8787", allowLocalHttp = true))
        assertNotNull(SyncUrls.problem("not a url", allowLocalHttp = true))
        assertEquals("https://x.io/api/v1/sync", SyncUrls.recordsEndpoint(" https://x.io/ "))
        assertEquals("https://x.io/api/v1/photos/dev/5", SyncUrls.photoEndpoint("https://x.io", "dev", 5))
    }
}
