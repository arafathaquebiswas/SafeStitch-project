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
}
