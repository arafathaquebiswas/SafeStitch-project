package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardCalculatorTest {
    private val day = FindingDefaults.DAY_MILLIS
    private val now = 1_800_000_000_000L

    private fun insp(daysAgo: Long?, outcome: Outcome, score: Int, group: String = "F › B1") =
        DashboardInspection(daysAgo?.let { now - it * day }, outcome, score, group)

    private fun action(status: ActionStatus, priority: Priority = Priority.MEDIUM, dueInDays: Long = 3, resolvedDaysAgo: Long? = null, verifiedDaysAgo: Long? = null) =
        DashboardAction(status, priority, now + dueInDays * day, resolvedDaysAgo?.let { now - it * day }, verifiedDaysAgo?.let { now - it * day })

    @Test
    fun emptyDataShowsDashesNotZeroPercent() {
        val s = DashboardCalculator.compute(emptyList(), emptyList(), now)
        assertEquals(0, s.completed)
        assertNull(s.passRatePercent)
        assertNull(s.averageScorePercent)
        assertNull(s.onTimeClosurePercent)
        assertEquals(0, s.openActions)
        assertEquals(0, s.totalInspections)
    }

    @Test
    fun windowCountsOnlyInspectionsSubmittedInTheLast30Days() {
        val s = DashboardCalculator.compute(
            listOf(
                insp(1, Outcome.PASS, 100),
                insp(10, Outcome.FAIL, 50),
                insp(29, Outcome.PASS, 90),
                insp(31, Outcome.FAIL, 0), // outside the window
                insp(null, Outcome.IN_PROGRESS, 0), // draft
            ),
            emptyList(),
            now,
        )
        assertEquals(3, s.completed)
        assertEquals(2, s.passed)
        assertEquals(1, s.failed)
        assertEquals(67, s.passRatePercent) // 2/3 = 66.7 -> 67
        assertEquals(80, s.averageScorePercent) // (100+50+90)/3
        assertEquals(1, s.drafts)
        assertEquals(5, s.totalInspections)
    }

    @Test
    fun correctiveActionCountsForAllFourStatuses() {
        val s = DashboardCalculator.compute(
            emptyList(),
            listOf(
                action(ActionStatus.OPEN, Priority.CRITICAL, dueInDays = -2), // open, overdue, critical
                action(ActionStatus.OPEN, Priority.LOW, dueInDays = 5), // open
                action(ActionStatus.IN_PROGRESS, Priority.MEDIUM, dueInDays = -1), // in progress, overdue
                action(ActionStatus.RESOLVED, Priority.HIGH, dueInDays = -1, resolvedDaysAgo = 2), // awaiting, not overdue
                action(ActionStatus.VERIFIED, Priority.HIGH, dueInDays = -5, resolvedDaysAgo = 6, verifiedDaysAgo = 4), // on time
                action(ActionStatus.VERIFIED, Priority.MEDIUM, dueInDays = -10, resolvedDaysAgo = 3, verifiedDaysAgo = 2), // late
                action(ActionStatus.VERIFIED, Priority.MEDIUM, dueInDays = -60, resolvedDaysAgo = 61, verifiedDaysAgo = 45), // outside window
            ),
            now,
        )
        assertEquals(2, s.openActions)
        assertEquals(1, s.inProgressActions)
        assertEquals(2, s.overdueActions)
        assertEquals(1, s.awaitingVerification)
        assertEquals(2, s.openCriticalOrHigh)
        assertEquals(2, s.verifiedInWindow)
        assertEquals(7, s.totalActions)
        assertEquals(50, s.onTimeClosurePercent)
    }

    @Test
    fun locationsAreSortedWorstFirst() {
        val s = DashboardCalculator.compute(
            listOf(
                insp(1, Outcome.PASS, 100, "F › A"),
                insp(1, Outcome.PASS, 100, "F › A"),
                insp(1, Outcome.FAIL, 40, "F › B"),
                insp(1, Outcome.PASS, 90, "F › B"),
                insp(1, Outcome.PASS, 90, ""),
            ),
            emptyList(),
            now,
        )
        assertEquals(listOf("F › B", "F › A", "No location set"), s.byLocation.map { it.label })
        assertEquals(50, s.byLocation.first().passRatePercent)
    }

    @Test
    fun summaryDashboardComputesTotalPerformedPendingReportsAndRecentEquipmentUpdates() {
        val cpFire = com.safestitch.app.data.db.CheckpointEntity(
            id = 1L,
            code = "FE-12",
            name = "CO2 Extinguisher",
            area = "North Lobby",
            type = CheckpointType.FIRE_SAFETY,
            createdAt = 1_000L,
        )
        val completedInspection = com.safestitch.app.data.db.InspectionWithDetails(
            inspection = com.safestitch.app.data.db.InspectionEntity(
                id = 10L,
                checkpointId = 1L,
                inspectorName = "R. Ahmed",
                siteName = "Plant 1 › Lobby",
                startedAt = 2_000L,
                completedAt = 3_000L,
                reportFileName = "SafeStitch_FE-12_R10.pdf",
                equipmentCode = "FE-12",
                equipmentName = "CO2 Extinguisher",
                equipmentType = CheckpointType.FIRE_SAFETY,
            ),
            checkpoint = cpFire,
            items = listOf(
                com.safestitch.app.data.db.ChecklistItemEntity(
                    id = 1L,
                    inspectionId = 10L,
                    position = 0,
                    section = "Fire",
                    question = "Pressure in green zone",
                    critical = true,
                    result = ItemResult.PASS,
                ),
            ),
            photos = emptyList(),
            actions = emptyList(),
        )
        val draftInspection = completedInspection.copy(
            inspection = completedInspection.inspection.copy(
                id = 11L,
                startedAt = 4_000L,
                completedAt = null,
                reportFileName = null,
            ),
        )
        val capturedPending = com.safestitch.app.data.db.Inspection(
            id = 1L,
            timestamp = 5_000L,
            imagePath = "/data/user/0/com.safestitch.app/files/photos/seed_fire_extinguisher_fe12.jpg",
            statusTags = listOf(com.safestitch.app.data.db.Inspection.TAG_NEEDS_REVIEW),
        )
        val capturedPass = com.safestitch.app.data.db.Inspection(
            id = 2L,
            timestamp = 6_000L,
            imagePath = "/data/user/0/com.safestitch.app/files/photos/seed_electrical_panel_ep04.jpg",
            statusTags = listOf(com.safestitch.app.data.db.Inspection.TAG_PASS),
        )

        val summary = com.safestitch.app.ui.dashboard.SummaryDashboardCalculator.compute(
            inspections = listOf(completedInspection, draftInspection),
            capturedInspections = listOf(capturedPending, capturedPass),
            checkpoints = listOf(cpFire),
        )

        assertEquals(4, summary.totalInspectionsPerformed)
        assertEquals(2, summary.checklistInspectionsCount)
        assertEquals(2, summary.capturedPhotosCount)
        assertEquals(2, summary.pendingReportsCount) // 1 draft + 1 NEEDS_REVIEW captured photo
        assertEquals(4, summary.recentUpdates.size)
        assertEquals("cap-2", summary.recentUpdates.first().key) // newest timestamp (6_000L) first
    }
}
