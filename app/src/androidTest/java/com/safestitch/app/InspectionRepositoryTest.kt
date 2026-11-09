package com.safestitch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.safestitch.app.data.DuplicateCheckpointCodeException
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.SafeStitchDatabase
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.ChecklistTemplates
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InspectionRepositoryTest {
    private lateinit var database: SafeStitchDatabase
    private lateinit var repository: InspectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = SafeStitchDatabase.inMemory(context)
        repository = InspectionRepository(database, PhotoStorage(context))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun newCheckpointStartsInspectionFromTemplateAndResumesDraft() {
        runBlocking {
            assertNull(repository.findCheckpointByCode("SAFESTITCH:CP:SS-T-1"))
            val checkpointId = repository.createCheckpoint("ss-t-1", "Fire point 1", "Floor 2", CheckpointType.FIRE_SAFETY)
            assertEquals(checkpointId, repository.findCheckpointByCode("SAFESTITCH:CP:SS-T-1")?.id)

            val inspectionId = repository.startOrResumeInspection(checkpointId, "R. Ahmed", "Unit 3")
            val details = repository.getInspection(inspectionId)!!
            assertEquals(ChecklistTemplates.forType(CheckpointType.FIRE_SAFETY).size, details.items.size)
            assertEquals("R. Ahmed", details.inspection.inspectorName)
            assertTrue(details.items.all { it.result == ItemResult.UNANSWERED })

            assertEquals(inspectionId, repository.startOrResumeInspection(checkpointId, "Other", "Other"))
        }
    }

    @Test
    fun duplicateCodesAreRejected() {
        runBlocking {
            repository.createCheckpoint("A1", "Line A1", "", CheckpointType.SEWING_LINE)
            try {
                repository.createCheckpoint("a1", "Again", "", CheckpointType.SEWING_LINE)
                fail("Expected DuplicateCheckpointCodeException")
            } catch (_: DuplicateCheckpointCodeException) {
                // expected
            }
        }
    }

    @Test
    fun answersPersistAndCompletionStartsAFreshInspectionNextTime() {
        runBlocking {
            val checkpointId = repository.createCheckpoint("E-9", "Panel 9", "", CheckpointType.ELECTRICAL)
            val inspectionId = repository.startOrResumeInspection(checkpointId, "Inspector", "")
            val items = repository.getInspection(inspectionId)!!.sortedItems
            items.forEach { repository.setItemResult(it.id, ItemResult.PASS) }
            repository.setItemResult(items.first().id, ItemResult.FAIL)
            repository.setItemNote(items.first().id, "Door hinge broken")

            val summary = repository.getInspection(inspectionId)!!.summary()
            assertEquals(items.size, summary.answered)
            assertEquals(1, summary.failed)
            assertEquals(Outcome.PASS, summary.outcome) // 11/12 = 91%, failed item is not critical

            repository.markCompleted(inspectionId, System.currentTimeMillis(), "report.pdf")
            assertNotEquals(inspectionId, repository.startOrResumeInspection(checkpointId, "Inspector", ""))
        }
    }
}
