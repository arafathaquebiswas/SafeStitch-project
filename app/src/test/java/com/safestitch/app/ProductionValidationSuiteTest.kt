package com.safestitch.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.safestitch.app.audio.AudioTranscriptionService
import com.safestitch.app.chat.ChatRolePersona
import com.safestitch.app.chat.FactoryChatContext
import com.safestitch.app.chat.SafetyChatService
import com.safestitch.app.data.AuthRepository
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.sync.HttpResult
import com.safestitch.app.sync.SyncClient
import com.safestitch.app.sync.SyncConfig
import com.safestitch.app.sync.SyncManager
import com.safestitch.app.sync.SyncOutcome
import com.safestitch.app.sync.SyncPayload
import com.safestitch.app.util.FileSharing
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import java.io.File
import java.io.IOException

/**
 * End-to-End Production Validation Suite covering the 5 critical field validation scenarios:
 * 1. Offline Test: Complete an inspection, capture evidence & after-fix photos, and create/update corrective actions with network disabled.
 * 2. Database Persistence Test: Close and reopen the on-disk SQLite database (`safestitch.db`) and verify all records & files survive restart.
 * 3. Synchronization & Deduplication Test: Reconnect to the sync server and verify pending records & photos upload idempotently without duplication.
 * 4. AI Online & Offline Test: Verify Gemini multi-turn chat (`gemini-4-hydrogen`, `gemini-3.1-flash-lite`) and voice transcription (`gemini-3.5-transcribe`) both online and when completely offline.
 * 5. PDF Export, Save, Share & Open Test: Generate A4 inspection & PDFBox reports, verify structure, save via ContentResolver, and verify FileProvider share/open intents.
 */
@RunWith(AndroidJUnit4::class)
class ProductionValidationSuiteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(SafeStitchDatabase.FILE_NAME)
        File(context.filesDir, "photos").deleteRecursively()
        File(context.filesDir, "reports").deleteRecursively()
    }

    private fun createTestJpeg(photoStorage: PhotoStorage, label: String): File {
        val file = photoStorage.newPhotoFile()
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(18, 42, 40))
            drawCircle(400f, 300f, 160f, Paint().apply { color = Color.rgb(11, 110, 105) })
            drawText(label, 80f, 100f, Paint().apply { color = Color.WHITE; textSize = 36f })
        }
        file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        bmp.recycle()
        return file
    }

    @Test
    fun Priority1_and_2_and_3_offlineInspectionPhotosActionsSurviveRestartAndSyncWithoutDuplication() = runBlocking {
        val photoStorage = PhotoStorage(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 1_762_900_000_000L

        var createdCheckpointId = -1L
        var createdInspectionId = -1L
        var createdActionId = -1L
        var createdCapturedId = -1L
        lateinit var evidencePhotoFile: File
        lateinit var afterFixPhotoFile: File

        // =========================================================================================
        // TEST 1: OFFLINE TEST (Wi-Fi & Mobile Data Disabled)
        // Complete an inspection, capture photos, and create + progress corrective actions offline.
        // =========================================================================================
        val db1 = SafeStitchDatabase.create(context)
        try {
            val auth1 = AuthRepository(context, db1, scope, clock = { now })
            val authErr = auth1.createFirstAdmin("Arafat Haque", "arafat.admin", "SafeStitch#2026")
            assertNull("Offline admin setup must succeed", authErr)
            val adminUser = auth1.currentUser
            assertNotNull("Admin user must be signed in offline", adminUser)
            adminUser!!

            val repo1 = InspectionRepository(db1, photoStorage, clock = { now })

            // Register equipment offline
            createdCheckpointId = repo1.createCheckpoint(
                code = "FE-101",
                name = "CO2 Extinguisher — Stitching Hall North",
                spot = "Pillar A4",
                type = CheckpointType.FIRE_EXTINGUISHER,
            )
            assertTrue(createdCheckpointId > 0L)

            // Start inspection offline
            createdInspectionId = repo1.startOrResumeInspection(
                checkpointId = createdCheckpointId,
                inspectorName = adminUser.displayName,
                inspectorUserId = adminUser.id,
            )
            val draft = repo1.getInspection(createdInspectionId)!!
            assertTrue("Inspection must load checklist questions offline", draft.sortedItems.size >= 3)

            // Answer checklist items: 1st item FAIL (critical), rest PASS
            val failedItem = draft.sortedItems.first()
            repo1.setItemResult(failedItem, ItemResult.FAIL)
            repo1.setItemNote(failedItem.id, "Pressure gauge needle in red zone; safety pin bent")
            draft.sortedItems.drop(1).forEach { item ->
                repo1.setItemResult(item, ItemResult.PASS)
            }
            repo1.setGeneralNotes(createdInspectionId, "Offline floor audit completed with zero connectivity.")

            // Capture evidence photo offline & attach to the failed checklist item
            evidencePhotoFile = createTestJpeg(photoStorage, "EVIDENCE_FE101")
            val evidencePhotoId = repo1.addPhoto(createdInspectionId, failedItem.id, evidencePhotoFile.name)
            assertTrue(evidencePhotoId > 0L)

            // Also capture a standalone camera inspection record offline
            createdCapturedId = repo1.recordCapturedInspection(
                imagePath = evidencePhotoFile.absolutePath,
                statusTags = listOf(Inspection.TAG_FAIL, Inspection.TAG_FOLLOW_UP),
                timestamp = now,
            )
            assertTrue(createdCapturedId > 0L)

            // Submit the inspection offline -> automatically creates 1 corrective action in a single SQLite transaction
            now += 60_000L
            val submitResult = repo1.submitInspection(
                inspectionId = createdInspectionId,
                completedAt = now,
                reportFileName = "SafeStitch_FE-101_offline.pdf",
            )
            assertFalse("First submission must not be marked alreadySubmitted", submitResult.alreadySubmitted)
            assertEquals("Exactly 1 corrective action must be created for the 1 failed item", 1, submitResult.createdActions)

            // Verify idempotent local submission: calling submitInspection a second time does not duplicate actions
            val duplicateSubmit = repo1.submitInspection(
                inspectionId = createdInspectionId,
                completedAt = now + 5_000L,
                reportFileName = "SafeStitch_FE-101_offline.pdf",
            )
            assertTrue("Second submit call must report alreadySubmitted", duplicateSubmit.alreadySubmitted)
            assertEquals(0, duplicateSubmit.createdActions)

            // Progress corrective action offline: OPEN -> IN_PROGRESS -> attach after-fix photo -> RESOLVED
            val actionRows = repo1.observeActionRows().first()
            assertEquals(1, actionRows.size)
            createdActionId = actionRows.single().action.id

            now += 120_000L
            assertTrue(repo1.startAction(createdActionId, adminUser))

            afterFixPhotoFile = createTestJpeg(photoStorage, "AFTER_FIX_FE101")
            val afterFixPhotoId = repo1.addAfterFixPhoto(createdActionId, afterFixPhotoFile.name)
            assertTrue(afterFixPhotoId > 0L)

            now += 180_000L
            assertTrue(repo1.resolveAction(createdActionId, "Replaced CO2 cylinder and installed new tamper seal", adminUser))

            // Verify pending sync queue counts while still offline
            assertEquals("1 inspection + 1 corrective action must be queued for sync", 2, repo1.observePendingSyncCount().first())
            assertEquals("2 photos (evidence + after-fix) must be queued for sync", 2, repo1.observePendingPhotoCount().first())
        } finally {
            // Close database completely to simulate app termination
            db1.close()
        }

        // =========================================================================================
        // TEST 2: DATABASE PERSISTENCE TEST (Application Restart)
        // Reopen SafeStitchDatabase from disk and verify every record, event, and photo survived.
        // =========================================================================================
        val db2 = SafeStitchDatabase.create(context)
        try {
            val auth2 = AuthRepository(context, db2, scope, clock = { now })
            auth2.refresh()
            assertNotNull("Signed-in admin session must survive app restart", auth2.currentUser)
            assertEquals("arafat.admin", auth2.currentUser?.username)

            val repo2 = InspectionRepository(db2, photoStorage, clock = { now })
            val catalog2 = CatalogRepository(db2)

            // Verify checkpoint survived
            val persistedCheckpoint = repo2.getCheckpoint(createdCheckpointId)
            assertNotNull("Checkpoint FE-101 must survive app restart", persistedCheckpoint)
            assertEquals("FE-101", persistedCheckpoint?.code)

            // Verify completed inspection, items, notes, and photos survived
            val persistedInspection = repo2.getInspection(createdInspectionId)
            assertNotNull("Completed inspection must survive app restart", persistedInspection)
            persistedInspection!!
            assertTrue(persistedInspection.isCompleted)
            assertEquals("Offline floor audit completed with zero connectivity.", persistedInspection.inspection.generalNotes)
            assertEquals(2, persistedInspection.photos.size)
            assertTrue("Evidence JPEG must exist on disk after restart", evidencePhotoFile.isFile && evidencePhotoFile.length() > 0L)
            assertTrue("After-fix JPEG must exist on disk after restart", afterFixPhotoFile.isFile && afterFixPhotoFile.length() > 0L)

            // Verify corrective action and its 3-step audit history survived
            val persistedActionRow = repo2.observeActionRow(createdActionId).first()
            assertNotNull("Corrective action must survive app restart", persistedActionRow)
            assertEquals(ActionStatus.RESOLVED, persistedActionRow?.action?.status)
            assertEquals("Replaced CO2 cylinder and installed new tamper seal", persistedActionRow?.action?.resolutionNote)

            val actionEvents = repo2.observeActionEvents(createdActionId).first()
            assertEquals("Audit trail must contain 3 events (OPEN, IN_PROGRESS, RESOLVED)", 3, actionEvents.size)

            // Verify captured_inspections survived
            val capturedRecord = repo2.getCapturedInspection(createdCapturedId)
            assertNotNull(capturedRecord)
            assertEquals(listOf(Inspection.TAG_FAIL, Inspection.TAG_FOLLOW_UP), capturedRecord?.normalizedTags)

            // =====================================================================================
            // TEST 3: SYNCHRONIZATION & DEDUPLICATION TEST (Reconnect to Internet)
            // Verify offline records sync when online, and re-syncing produces zero duplicates.
            // =====================================================================================
            val serverInspectionsByKey = linkedMapOf<String, Long>() // key: "$deviceId:$localId" -> revision
            val serverActionsByKey = linkedMapOf<String, Long>()     // key: "$deviceId:$localId" -> revision
            val serverPhotosByKey = linkedMapOf<String, String>()    // key: "$deviceId:$photoId" -> sha256
            var networkOnline = false

            val idempotentSyncClient = object : SyncClient() {
                override fun postJson(url: String, apiKey: String, body: String): HttpResult {
                    if (!networkOnline) throw IOException("No network connection")
                    val root = Json.parseToJsonElement(body).jsonObject
                    val deviceId = root["device_id"]!!.jsonPrimitive.content
                    val results = mutableListOf<String>()

                    root["inspections"]?.jsonArray?.forEach { elem ->
                        val obj = elem.jsonObject
                        val localId = obj["local_id"]!!.jsonPrimitive.long
                        val rev = obj["revision"]!!.jsonPrimitive.long
                        val key = "$deviceId:$localId"
                        val prevRev = serverInspectionsByKey[key]
                        val status = if (prevRev == rev) "unchanged" else {
                            serverInspectionsByKey[key] = rev
                            "stored"
                        }
                        results += """{"type":"inspection","local_id":$localId,"status":"$status"}"""
                    }

                    root["corrective_actions"]?.jsonArray?.forEach { elem ->
                        val obj = elem.jsonObject
                        val localId = obj["local_id"]!!.jsonPrimitive.long
                        val rev = obj["revision"]!!.jsonPrimitive.long
                        val key = "$deviceId:$localId"
                        val prevRev = serverActionsByKey[key]
                        val status = if (prevRev == rev) "unchanged" else {
                            serverActionsByKey[key] = rev
                            "stored"
                        }
                        results += """{"type":"corrective_action","local_id":$localId,"status":"$status"}"""
                    }

                    return HttpResult(200, """{"results":[${results.joinToString(",")}]}""")
                }

                override fun putPhoto(url: String, apiKey: String, file: File, sha256: String, photo: PhotoEntity): HttpResult {
                    if (!networkOnline) throw IOException("No network connection")
                    val key = "${photo.inspectionId}:${photo.id}"
                    val existingSha = serverPhotosByKey[key]
                    if (existingSha != null && existingSha != sha256) {
                        return HttpResult(409, """{"error":"sha256 mismatch"}""")
                    }
                    serverPhotosByKey[key] = sha256
                    return HttpResult(200, """{"status":"ok"}""")
                }
            }

            val syncManager = SyncManager(
                context = context,
                repository = repo2,
                catalog = catalog2,
                photoStorage = photoStorage,
                client = idempotentSyncClient,
                clock = { now },
            )
            assertNull(
                syncManager.save(
                    SyncConfig(
                        enabled = true,
                        serverUrl = "https://sync.safestitch.example.com",
                        apiKey = "ss_live_token_2026",
                    ),
                ),
            )

            // 3A. Attempt sync while still offline -> returns RETRY and preserves pending queue
            assertEquals(SyncOutcome.RETRY, syncManager.runSync())
            assertEquals("Records must remain queued when offline", 2, repo2.observePendingSyncCount().first())
            assertEquals("Photos must remain queued when offline", 2, repo2.observePendingPhotoCount().first())

            // 3B. Reconnect to the internet -> sync succeeds and clears pending queue
            networkOnline = true
            now += 10_000L
            assertEquals(SyncOutcome.SUCCESS, syncManager.runSync())
            assertEquals("Pending record count must be 0 after sync", 0, repo2.observePendingSyncCount().first())
            assertEquals("Pending photo count must be 0 after sync", 0, repo2.observePendingPhotoCount().first())
            assertEquals("Server must hold exactly 1 inspection", 1, serverInspectionsByKey.size)
            assertEquals("Server must hold exactly 1 corrective action", 1, serverActionsByKey.size)
            assertEquals("Server must hold exactly 2 photos", 2, serverPhotosByKey.size)
            assertTrue("No sync conflicts should exist", repo2.observeConflicts().first().isEmpty())

            // 3C. Force re-upload of all records (simulating duplicate retry) -> server deduplicates with 0 duplicates
            repo2.resetSyncState()
            assertEquals(2, repo2.observePendingSyncCount().first())
            assertEquals(SyncOutcome.SUCCESS, syncManager.runSync())
            assertEquals(0, repo2.observePendingSyncCount().first())
            assertEquals("Re-sync must NOT duplicate inspections on server", 1, serverInspectionsByKey.size)
            assertEquals("Re-sync must NOT duplicate corrective actions on server", 1, serverActionsByKey.size)
            assertEquals("Re-sync must NOT duplicate photos on server", 2, serverPhotosByKey.size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun Priority4_aiChatAndVoiceTranscriptionWorkBothWithAndWithoutInternet() = runBlocking {
        // =========================================================================================
        // TEST 4: AI TEST (Gemini Chat & Voice Transcription Online + Offline)
        // =========================================================================================
        val db = SafeStitchDatabase.create(context)
        try {
            val photoStorage = PhotoStorage(context)
            val repo = InspectionRepository(db, photoStorage)
            val cpId = repo.createCheckpoint(
                code = "FE-101",
                name = "CO2 Extinguisher — Main Hall",
                spot = "Bay 2",
                type = CheckpointType.FIRE_EXTINGUISHER,
            )
            assertTrue(cpId > 0L)

            val factoryContext = FactoryChatContext(
                checkpoints = repo.getCheckpoints(),
                inspections = repo.observeInspections().first(),
                actions = repo.observeActionRows().first(),
                capturedPhotos = repo.observeCapturedInspections().first(),
            )

            // 4A. Online Mode: Connect to live local Gemini server (http://127.0.0.1:3000)
            val onlineChat = SafetyChatService(serverEndpointUrl = "http://127.0.0.1:3000/api/gemini/chat")
            val initialHistory = listOf(onlineChat.initialGreeting(ChatRolePersona.SAFETY_AUDITOR))
            val (onlineHistory, onlineReply) = onlineChat.sendMessage(
                userPrompt = "What should I check on FE-101?",
                history = initialHistory,
                persona = ChatRolePersona.SAFETY_AUDITOR,
                modelId = ChatRolePersona.MODEL_GENERAL,
                context = factoryContext,
            )
            assertEquals("Multi-turn history must include greeting + user message + assistant reply", 3, onlineHistory.size)
            assertEquals("gemini-4-hydrogen", onlineReply.modelId)
            assertTrue("Online reply must reference FE-101", onlineReply.text.contains("FE-101"))

            val onlineTranscriber = AudioTranscriptionService(serverEndpointUrl = "http://127.0.0.1:3000/api/gemini/transcribe")
            val pcm = AudioTranscriptionService.synthesizeTonePcm16(durationMs = 400, sampleRate = 16_000)
            val wavBytes = AudioTranscriptionService.wrapPcm16MonoAsWav(pcm, sampleRate = 16_000)
            val onlineTranscript = onlineTranscriber.transcribeAudioBytes(wavBytes, contextHint = "FE-101")
            assertEquals("gemini-3.5-transcribe", onlineTranscript.model)
            assertTrue("Online transcript must reference FE-101", onlineTranscript.text.contains("FE-101"))

            // 4B. Offline Mode (Wi-Fi and mobile data disabled -> unreachable endpoint)
            val offlineChat = SafetyChatService(serverEndpointUrl = "http://127.0.0.1:9/offline-unreachable")
            val (offlineHistory, offlineReply) = offlineChat.sendMessage(
                userPrompt = "What should I check on FE-101?",
                history = initialHistory,
                persona = ChatRolePersona.MAINTENANCE_ADVISOR,
                modelId = ChatRolePersona.MODEL_FAST,
                context = factoryContext,
            )
            assertEquals(3, offlineHistory.size)
            assertEquals("gemini-3.1-flash-lite", offlineReply.modelId)
            assertTrue("Offline chat fallback must still answer with FE-101 details", offlineReply.text.contains("FE-101"))

            val offlineTranscriber = AudioTranscriptionService(serverEndpointUrl = "http://127.0.0.1:9/offline-unreachable")
            val offlineTranscript = offlineTranscriber.transcribeAudioBytes(wavBytes, contextHint = "FE-101")
            assertEquals("gemini-3.5-transcribe", offlineTranscript.model)
            assertTrue("Offline transcription must include equipment context hint FE-101", offlineTranscript.text.contains("FE-101"))
        } finally {
            db.close()
        }
    }

    @Test
    fun Priority5_pdfExportSaveShareAndOpenOnAndroidDevice() = runBlocking {
        // =========================================================================================
        // TEST 5: PDF TEST (Export, Save, Share & Open Reports on Android)
        // =========================================================================================
        val db = SafeStitchDatabase.create(context)
        try {
            val photoStorage = PhotoStorage(context)
            val repo = InspectionRepository(db, photoStorage)
            val reportService = ReportService(context, repo, photoStorage)

            val cpId = repo.createCheckpoint(
                code = "EL-204",
                name = "Main Distribution Panel EL-204",
                spot = "Electrical Room B",
                type = CheckpointType.ELECTRICAL,
            )
            val inspectionId = repo.startOrResumeInspection(cpId, "Arafat Haque", 1L)
            val draft = repo.getInspection(inspectionId)!!
            repo.setItemResult(draft.sortedItems.first(), ItemResult.FAIL)
            repo.setItemNote(draft.sortedItems.first().id, "Missing inner dead-front cover screw")
            draft.sortedItems.drop(1).forEach { repo.setItemResult(it, ItemResult.PASS) }

            val photoFile = createTestJpeg(photoStorage, "PANEL_EL204")
            repo.addPhoto(inspectionId, draft.sortedItems.first().id, photoFile.name)

            val completedAt = 1_762_950_000_000L
            val pdfReportFile = reportService.generateReport(inspectionId, completedAt)
            repo.submitInspection(inspectionId, completedAt, pdfReportFile.name)

            // 5A. Verify standard A4 Inspection PDF report file & structure
            assertTrue("Standard A4 report PDF must exist on disk", pdfReportFile.isFile && pdfReportFile.length() > 500L)
            PDDocument.load(pdfReportFile).use { doc ->
                assertTrue("Standard A4 report must have at least 1 page", doc.numberOfPages >= 1)
            }

            // 5B. Verify PDFBox Branded Captured Inspection A4 Report
            val capturedId = repo.recordCapturedInspection(
                imagePath = photoFile.absolutePath,
                statusTags = listOf(Inspection.TAG_FAIL, Inspection.TAG_FOLLOW_UP),
                timestamp = completedAt,
            )
            val capturedEntity = repo.getCapturedInspection(capturedId)!!
            reportService.setSelectedCheckpointId(capturedId, cpId)
            val equipDetails = repo.resolveCapturedEquipmentDetails(capturedEntity, cpId, "Arafat Haque")
            val capturedPdfFile = reportService.exportCapturedInspectionPdf(capturedEntity, equipDetails)

            assertTrue("Captured inspection PDFBox A4 report must exist", capturedPdfFile.isFile && capturedPdfFile.length() > 500L)
            PDDocument.load(capturedPdfFile).use { doc ->
                assertEquals(1, doc.numberOfPages)
                val text = PDFTextStripper().getText(doc)
                assertTrue("PDF text must include equipment code EL-204", text.contains("EL-204"))
                assertTrue("PDF text must include equipment name", text.contains("Main Distribution Panel"))
                assertTrue("PDF text must include inspector name Arafat Haque", text.contains("Arafat Haque"))
                assertTrue("PDF text must include status tag FAIL", text.contains("FAIL"))
            }

            // 5C. Verify "Save PDF" (FileSharing.copyTo via ContentResolver) produces an identical byte-for-byte PDF
            val savedDestinationFile = File(context.cacheDir, "UserSaved_EL-204_Report.pdf")
            FileSharing.copyTo(context, capturedPdfFile, Uri.fromFile(savedDestinationFile))
            assertTrue("Saved PDF copy must exist at destination URI", savedDestinationFile.isFile)
            assertEquals("Saved PDF size must match exported PDF byte-for-byte", capturedPdfFile.length(), savedDestinationFile.length())
            assertEquals(SyncManager.sha256(capturedPdfFile), SyncManager.sha256(savedDestinationFile))
            savedDestinationFile.delete()

            // 5D. Verify "Share PDF" (FileSharing.sharePdf) launches ACTION_CHOOSER with FileProvider content:// URI & read permission
            val shadowApp = Shadows.shadowOf(context as Application)
            shadowApp.clearNextStartedActivities()
            FileSharing.sharePdf(context, capturedPdfFile, "SafeStitch Report EL-204")
            val chooserIntent = shadowApp.nextStartedActivity
            assertNotNull("FileSharing.sharePdf must launch a chooser Intent", chooserIntent)
            assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
            @Suppress("DEPRECATION")
            val sendIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(sendIntent)
            assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
            assertEquals("application/pdf", sendIntent.type)
            assertTrue("Share intent must grant FLAG_GRANT_READ_URI_PERMISSION", (sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            @Suppress("DEPRECATION")
            val sharedUri = sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(sharedUri)
            assertEquals("content", sharedUri!!.scheme)
            assertEquals("${context.packageName}.fileprovider", sharedUri.authority)

            // 5E. Verify "Open PDF" (FileSharing.openPdf) launches ACTION_VIEW with FileProvider content:// URI
            shadowApp.clearNextStartedActivities()
            FileSharing.openPdf(context, capturedPdfFile)
            val viewIntent = shadowApp.nextStartedActivity
            assertNotNull("FileSharing.openPdf must launch an ACTION_VIEW Intent", viewIntent)
            assertEquals(Intent.ACTION_VIEW, viewIntent.action)
            assertEquals("application/pdf", viewIntent.type)
            assertEquals("content", viewIntent.data?.scheme)
            assertEquals("${context.packageName}.fileprovider", viewIntent.data?.authority)
            assertTrue("Open intent must grant FLAG_GRANT_READ_URI_PERMISSION", (viewIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        } finally {
            db.close()
        }
    }
}
