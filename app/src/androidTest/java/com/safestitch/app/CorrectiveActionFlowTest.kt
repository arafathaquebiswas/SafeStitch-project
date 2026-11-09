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

    @Test
    fun failingOneItemCreatesExactlyOneActionThatGoesThroughAdminVerification() = runBlocking {
        val factory = catalog.addLocation(LocationLevel.FACTORY, -1, "Main factory")
        val building = catalog.addLocation(LocationLevel.BUILDING, factory, "Building 1")
        val floor = catalog.addLocation(LocationLevel.FLOOR, building, "Floor 2")
        val area = catalog.addLocation(LocationLevel.AREA, floor, "Cutting")
        val cp = repo.createCheckpoint("FE-12", "Extinguisher 12", "Stair B", CheckpointType.FIRE_SAFETY, area)

        val id = repo.startOrResumeInspection(cp, inspector.displayName, inspector.id)
        val details = repo.getInspection(id)!!
        assertEquals("Main factory › Building 1 › Floor 2 › Cutting", details.inspection.siteName)
        val items = details.sortedItems
        items.drop(1).forEach { repo.setItemResult(it, ItemResult.PASS) }
        repo.setItemResult(items[0], ItemResult.FAIL)
        repo.setItemNote(items[0].id, "Seal broken")
        val failed = repo.getInspection(id)!!.sortedItems[0]
        assertEquals(Priority.CRITICAL, failed.priority) // first fire question is critical
        assertNotNull(failed.dueAt)

        assertEquals(1, repo.submitInspection(id, System.currentTimeMillis(), "report.pdf"))
        assertEquals(1, repo.countActions())
        try {
            repo.submitInspection(id, System.currentTimeMillis(), "report.pdf")
            fail("Submitting twice must not be possible")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(1, repo.countActions())

        val action = repo.observeActionRows().first().single().action
        assertEquals(ActionStatus.OPEN, action.status)
        assertEquals("Seal broken", action.description)

        // Inspector reports the fix; an inspector cannot verify; an admin can.
        assertTrue(repo.resolveAction(action.id, "Replaced the seal", inspector))
        try {
            repo.verifyAction(action.id, inspector)
            fail("Inspectors must not verify")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(repo.verifyAction(action.id, admin))
        val verified = repo.observeActionRow(action.id).first()!!.action
        assertEquals(ActionStatus.VERIFIED, verified.status)
        assertEquals("Admin Rahman", verified.verifiedByName)
        assertFalse(repo.resolveAction(action.id, "again", inspector)) // closed actions stay closed

        // Sync queue: the inspection and the action are pending until marked synced.
        assertEquals(1, repo.pendingSyncInspections(10).size)
        assertEquals(1, repo.pendingSyncActions(10).size)
        repo.markSynced(listOf(id), listOf(action.id), System.currentTimeMillis() + 1)
        assertEquals(0, repo.observePendingSyncCount().first())
    }

    @Test
    fun adminCanReopenAResolvedAction() = runBlocking {
        val cp = repo.createCheckpoint("EP-1", "Panel 1", "", CheckpointType.ELECTRICAL)
        val id = repo.startOrResumeInspection(cp, inspector.displayName, inspector.id)
        val items = repo.getInspection(id)!!.sortedItems
        items.forEach { repo.setItemResult(it, ItemResult.PASS) }
        repo.setItemResult(items[2], ItemResult.FAIL)
        repo.setItemNote(items[2].id, "Labels missing")
        repo.submitInspection(id, System.currentTimeMillis(), "r.pdf")
        val actionId = repo.observeActionRows().first().single().action.id
        repo.resolveAction(actionId, "Labelled", inspector)
        assertTrue(repo.reopenAction(actionId, admin, "Two breakers still unlabelled"))
        val reopened = repo.observeActionRow(actionId).first()!!.action
        assertEquals(ActionStatus.OPEN, reopened.status)
        assertEquals("Two breakers still unlabelled", reopened.adminComment)
    }

    @Test
    fun editedQuestionsApplyToNewInspectionsOnly() = runBlocking {
        val cp = repo.createCheckpoint("GA-1", "Store room", "", CheckpointType.GENERAL_AREA)
        val first = repo.startOrResumeInspection(cp, "Rina", 2)
        val before = repo.getInspection(first)!!.items.size
        catalog.addQuestion(CheckpointType.GENERAL_AREA, "Housekeeping & storage", "Spill kit is available", critical = false)
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
