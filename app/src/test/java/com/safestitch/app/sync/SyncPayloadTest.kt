package com.safestitch.app.sync

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncPayloadTest {
    private val t0 = Instant.parse("2026-11-09T08:00:00Z").toEpochMilli()

    private val details = InspectionWithDetails(
        inspection = InspectionEntity(
            id = 7, checkpointId = 3, inspectorName = "Rina Akter", siteName = "Main › B1 › Floor 2 › Cutting",
            startedAt = t0, completedAt = t0 + 600_000, reportFileName = "r.pdf", updatedAt = t0 + 600_000,
        ),
        checkpoint = CheckpointEntity(id = 3, code = "FE-12", name = "Extinguisher 12", area = "Stair B", type = CheckpointType.FIRE_SAFETY, createdAt = t0, areaId = 4),
        items = listOf(
            ChecklistItemEntity(id = 2, inspectionId = 7, position = 1, section = "Fire", question = "Q2", critical = false, result = ItemResult.PASS),
            ChecklistItemEntity(
                id = 1, inspectionId = 7, position = 0, section = "Fire", question = "Q1", critical = true,
                result = ItemResult.FAIL, note = "Seal broken", priority = Priority.CRITICAL, dueAt = t0 + 86_400_000,
            ),
        ),
        photos = listOf(PhotoEntity(id = 1, inspectionId = 7, itemId = 1, fileName = "a.jpg", takenAt = t0)),
        actions = emptyList(),
    )
    private val path = AreaPath(4, "Cutting", 3, "Floor 2", 2, "B1", 1, "Main")

    @Test
    fun inspectionPayloadHasStableFieldsAndIsoTimes() {
        val dto = SyncPayload.inspection(details, path)
        val request = SyncRequest(deviceId = "dev-1", appVersion = "1.0.0", sentAt = SyncPayload.iso(t0), inspections = listOf(dto), correctiveActions = emptyList())
        val json = Json.parseToJsonElement(SyncPayload.encode(request)).jsonObject
        assertEquals(1, json["schema"]!!.jsonPrimitive.int)
        assertEquals("dev-1", json["device_id"]!!.jsonPrimitive.content)
        val insp = json["inspections"]!!.jsonArray[0].jsonObject
        assertEquals("FE-12", insp["equipment_code"]!!.jsonPrimitive.content)
        assertEquals("2026-11-09T08:10:00Z", insp["completed_at"]!!.jsonPrimitive.content)
        assertEquals("FAIL", insp["result"]!!.jsonPrimitive.content) // critical item failed
        assertEquals("Cutting", insp["location"]!!.jsonObject["area"]!!.jsonPrimitive.content)
        val items = insp["items"]!!.jsonArray
        assertEquals("1", items[0].jsonObject["position"]!!.jsonPrimitive.content) // sorted, 1-based
        assertEquals("CRITICAL", items[0].jsonObject["priority"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10T08:00:00Z", items[0].jsonObject["due_at"]!!.jsonPrimitive.content)
        assertEquals(1, insp["photo_count"]!!.jsonPrimitive.int)
        assertFalse("payload must not contain passwords", SyncPayload.encode(request).contains("password", ignoreCase = true))
    }

    @Test
    fun actionPayload() {
        val row = ActionRow(
            action = CorrectiveActionEntity(
                id = 9, inspectionId = 7, itemId = 1, description = "Seal broken", priority = Priority.CRITICAL,
                dueAt = t0 + 86_400_000, status = ActionStatus.VERIFIED, createdAt = t0, resolutionNote = "Replaced",
                resolvedByName = "Rina Akter", resolvedAt = t0 + 3_600_000, verifiedByName = "Admin", verifiedAt = t0 + 7_200_000,
            ),
            question = "Q1", section = "Fire", itemPosition = 0, checkpointId = 3, checkpointName = "Extinguisher 12",
            checkpointCode = "FE-12", checkpointAreaId = 4, spot = "", locationSnapshot = "", inspectorName = "Rina Akter",
            inspectionCompletedAt = t0,
        )
        val dto = SyncPayload.action(row)
        assertEquals(9, dto.localId)
        assertEquals("VERIFIED", dto.status)
        assertEquals("2026-11-09T10:00:00Z", dto.verifiedAt)
        assertEquals(1, dto.itemPosition)
        assertNull(dto.adminComment)
    }

    @Test
    fun serverAddressRules() {
        assertNull(SyncUrls.problem("https://sync.example.com", allowLocalHttp = false))
        assertNotNull(SyncUrls.problem("http://sync.example.com", allowLocalHttp = true))
        assertNotNull(SyncUrls.problem("http://10.0.2.2:8787", allowLocalHttp = false))
        assertNull(SyncUrls.problem("http://10.0.2.2:8787", allowLocalHttp = true))
        assertNotNull(SyncUrls.problem("not a url", allowLocalHttp = true))
        assertEquals("https://x.io/api/v1/sync", SyncUrls.endpoint(" https://x.io/ "))
        assertTrue(SyncPayload.iso(t0).endsWith("Z"))
    }
}
