package com.safestitch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.safestitch.app.data.AuthRepository
import com.safestitch.app.data.AuthState
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.LocationLevel
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.SyncKind
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Priority
import com.safestitch.app.domain.UserRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs entirely on a throwaway in-memory database; the app's real data is never touched. */
@RunWith(AndroidJUnit4::class)
class CorrectiveActionFlowTest {
    private lateinit var context: Context
    private lateinit var db: SafeStitchDatabase
    private lateinit var repo: InspectionRepository
    private lateinit var catalog: CatalogRepository

    private val admin = UserEntity(id = 1, username = "admin", displayName = "Admin Rahman", role = UserRole.ADMIN, passwordHash = "x", createdAt = 0)
    private val inspector = UserEntity(id = 2, username = "rina", displayName = "Rina Akter", role = UserRole.INSPECTOR, passwordHash = "x", createdAt = 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = SafeStitchDatabase.inMemory(context)
        repo = InspectionRepository(db, PhotoStorage(context))
        catalog = CatalogRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteSharedPreferences("test_session")
    }

    private suspend fun failOneAndSubmit(code: String, type: CheckpointType, responsible: UserEntity? = null): Long {
        val cp = repo.createCheckpoint(code, "Equipment $code", "", type)
        val id = repo.startOrResumeInspection(cp, inspector.displayName, inspector.id)
        val items = repo.getInspection(id)!!.sortedItems
        items.drop(1).forEach { repo.setItemResult(it, ItemResult.PASS) }
        repo.setItemResult(items[0], ItemResult.FAIL)
        repo.setItemNote(items[0].id, "Seal broken")
        responsible?.let { repo.setResponsible(items[0].id, it.displayName, it.id) }
        val result = repo.submitInspection(id, System.currentTimeMillis(), "report.pdf")
        assertEquals(1, result.createdActions)
        return id
    }

    @Test
    fun oneFailedItemGivesExactlyOneActionThroughAllFourStatusesWithAuditTrail() = runBlocking {
        val factory = catalog.addLocation(LocationLevel.FACTORY, -1, "Main factory")
        val building = catalog.addLocation(LocationLevel.BUILDING, factory, "Building 1")
        val floor = catalog.addLocation(LocationLevel.FLOOR, building, "Floor 2")
        val area = catalog.addLocation(LocationLevel.AREA, floor, "Cutting")
        val cp = repo.createCheckpoint("FE-12", "Extinguisher 12", "Stair B", CheckpointType.FIRE_EXTINGUISHER, area)
        val id = repo.startOrResumeInspection(cp, inspector.displayName, inspector.id)
        val details = repo.getInspection(id)!!
        assertEquals("Main factory › Building 1 › Floor 2 › Cutting", details.inspection.siteName)
        assertEquals("Extinguisher 12", details.inspection.equipmentName)
        val items = details.sortedItems
        items.drop(1).forEach { repo.setItemResult(it, ItemResult.PASS) }
        repo.setItemResult(items[1], ItemResult.FAIL) // pressure gauge, critical
        repo.setItemNote(items[1].id, "Gauge in red zone")
        val failed = repo.getInspection(id)!!.sortedItems[1]
        assertEquals(Priority.CRITICAL, failed.priority)
        assertEquals("Rina Akter", failed.responsibleName) // defaults to the inspector
        assertNotNull(failed.dueAt)

        // Submit, then retry the submission: still exactly one inspection and one action.
        assertEquals(1, repo.submitInspection(id, System.currentTimeMillis(), "report.pdf").createdActions)
        assertTrue(repo.submitInspection(id, System.currentTimeMillis(), "report.pdf").alreadySubmitted)
        assertEquals(1, repo.countInspectionsFor(cp))
        assertEquals(1, repo.countActions())

        val action = repo.observeActionRows().first().single().action
        assertEquals(ActionStatus.OPEN, action.status)
        assertEquals("Gauge in red zone", action.description)
        assertEquals("Rina Akter", action.responsibleName)
        assertEquals(Priority.CRITICAL, action.priority)

        // Steps must happen in order.
        assertFalse(repo.resolveAction(action.id, "skipped a step", inspector))
        assertTrue(repo.startAction(action.id, inspector))
        assertFalse(repo.startAction(action.id, inspector))
        repo.addAfterFixPhoto(action.id, "IMG_after_fix_test.jpg")
        assertTrue(repo.resolveAction(action.id, "Recharged and resealed", inspector))
        try {
            repo.verifyAction(action.id, inspector)
            fail("Inspectors must not verify")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertEquals(ActionStatus.RESOLVED, repo.observeActionRow(action.id).first()!!.action.status)
        assertTrue(repo.verifyAction(action.id, admin))
        assertFalse(repo.resolveAction(action.id, "again", inspector)) // closed actions stay closed

        val verified = repo.observeActionRow(action.id).first()!!.action
        assertEquals(ActionStatus.VERIFIED, verified.status)
        assertEquals("Rina Akter", verified.startedByName)
        assertEquals("Rina Akter", verified.resolvedByName)
        assertEquals("Admin Rahman", verified.verifiedByName)
        assertTrue(verified.startedAt!! <= verified.resolvedAt!! && verified.resolvedAt!! <= verified.verifiedAt!!)

        val events = repo.observeActionEvents(action.id).first()
        assertEquals(listOf(null, ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.RESOLVED), events.map { it.fromStatus })
        assertEquals(listOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.RESOLVED, ActionStatus.VERIFIED), events.map { it.toStatus })
        assertEquals(listOf("Rina Akter", "Rina Akter", "Rina Akter", "Admin Rahman"), events.map { it.byName })

        val photos = repo.getInspection(id)!!.photos
        assertEquals(1, photos.count { it.actionId == action.id })

        // Sync queue: inspection + action pending until marked synced; then the photos become uploadable.
        assertEquals(1, repo.pendingSyncInspections(10).size)
        val bundles = repo.pendingSyncActions(10)
        assertEquals(1, bundles.size)
        assertEquals(4, bundles[0].events.size)
        assertEquals(1, bundles[0].photos.size)
        assertTrue(repo.pendingPhotos(10).isEmpty()) // parents not on the server yet
        repo.markSynced(listOf(id), listOf(action.id), System.currentTimeMillis() + 1)
        assertEquals(0, repo.observePendingSyncCount().first())
        assertEquals(1, repo.pendingPhotos(10).size)
    }

    @Test
    fun adminCanSendBackAResolvedActionAndConflictsAreRecorded() = runBlocking {
        val id = failOneAndSubmit("EP-1", CheckpointType.ELECTRICAL, responsible = admin)
        val action = repo.observeActionRows().first().single().action
        assertEquals("Admin Rahman", action.responsibleName)
        repo.startAction(action.id, inspector)
        repo.resolveAction(action.id, "Labelled", inspector)
        assertTrue(repo.sendBackAction(action.id, admin, "Two breakers still unlabelled"))
        val back = repo.observeActionRow(action.id).first()!!.action
        assertEquals(ActionStatus.IN_PROGRESS, back.status)
        assertEquals("Two breakers still unlabelled", back.adminComment)

        repo.setConflict(SyncKind.CORRECTIVE_ACTION, action.id, "Server has a newer version")
        val conflicts = repo.observeConflicts().first()
        assertEquals(1, conflicts.size)
        assertEquals(0, repo.pendingSyncActions(10).size) // conflicts are not retried blindly
        repo.resolveConflict(conflicts[0], sendMine = true)
        assertEquals(0, repo.observeConflicts().first().size)
        assertEquals(1, repo.pendingSyncActions(10).size)
        assertTrue(id > 0)
    }

    @Test
    fun editedQuestionsApplyToNewInspectionsOnly() = runBlocking {
        val cp = repo.createCheckpoint("BL-1", "Boiler 1", "", CheckpointType.BOILER)
        val first = repo.startOrResumeInspection(cp, "Rina", 2)
        val before = repo.getInspection(first)!!.items.size
        catalog.addQuestion(CheckpointType.BOILER, "Boiler safety", "Blowdown valve operates freely", critical = false)
        repo.submitInspection(first, System.currentTimeMillis(), "r.pdf")
        val second = repo.startOrResumeInspection(cp, "Rina", 2)
        assertEquals(before, repo.getInspection(first)!!.items.size)
        assertEquals(before + 1, repo.getInspection(second)!!.items.size)
    }

    @Test
    fun accountsSetupSignInRolesAndDeactivation() = runBlocking {
        val auth = AuthRepository(context, db, CoroutineScope(Dispatchers.Unconfined), prefsName = "test_session")
        auth.refresh()
        assertEquals(AuthState.NeedsSetup, auth.state.value)
        assertNull(auth.createFirstAdmin("Admin Rahman", "Admin", "admin-pass-1"))
        val adminUser = (auth.state.value as AuthState.SignedIn).user
        assertTrue(adminUser.isAdmin)
        assertNotNull(auth.createFirstAdmin("Another", "other", "admin-pass-2")) // only once
        assertNull(auth.createUser(adminUser, "Rina Akter", "rina", UserRole.INSPECTOR, "inspect-123"))
        assertNotNull(auth.createUser(adminUser, "Dup", "RINA", UserRole.INSPECTOR, "inspect-123")) // username taken
        auth.signOut()
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertNotNull(auth.signIn("rina", "wrong-password"))
        assertNull(auth.signIn("Rina", "inspect-123"))
        val rina = (auth.state.value as AuthState.SignedIn).user
        assertEquals(UserRole.INSPECTOR, rina.role)
        assertNotNull(auth.createUser(rina, "X", "xuser", UserRole.ADMIN, "whatever-1")) // inspectors cannot add users
        assertNotNull(auth.setActive(adminUser, adminUser.id, false)) // cannot deactivate yourself / last admin
        assertNull(auth.setActive(adminUser, rina.id, false))
        auth.signOut()
        assertNotNull(auth.signIn("rina", "inspect-123")) // deactivated
    }
}
