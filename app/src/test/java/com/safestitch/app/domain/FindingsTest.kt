package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FindingsTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(dhaka).toInstant().toEpochMilli()

    @Test
    fun criticalItemsDefaultToCriticalOthersToMedium() {
        assertEquals(Priority.CRITICAL, FindingDefaults.priorityFor(criticalItem = true))
        assertEquals(Priority.MEDIUM, FindingDefaults.priorityFor(criticalItem = false))
    }

    @Test
    fun dueDateIsEndOfDayAfterThePriorityDays() {
        val found = at(2026, 11, 9, 14, 30)
        assertEquals(at(2026, 11, 10, 23, 59), FindingDefaults.dueAt(Priority.CRITICAL, found, dhaka))
        assertEquals(at(2026, 11, 12, 23, 59), FindingDefaults.dueAt(Priority.HIGH, found, dhaka))
        assertEquals(at(2026, 11, 16, 23, 59), FindingDefaults.dueAt(Priority.MEDIUM, found, dhaka))
        assertEquals(at(2026, 11, 23, 23, 59), FindingDefaults.dueAt(Priority.LOW, found, dhaka))
    }

    @Test
    fun onlyOpenActionsPastDueAreOverdue() {
        val due = at(2026, 11, 10, 23, 59)
        val after = due + 1
        assertTrue(FindingDefaults.isOverdue(ActionStatus.OPEN, due, after))
        assertFalse(FindingDefaults.isOverdue(ActionStatus.OPEN, due, due))
        assertFalse(FindingDefaults.isOverdue(ActionStatus.RESOLVED, due, after))
        assertFalse(FindingDefaults.isOverdue(ActionStatus.VERIFIED, due, after))
    }

    @Test
    fun failedItemsNeedPriorityAndDueDateToSubmit() {
        val problems = CompletionRules.problems(
            listOf(ItemOutcome(ItemResult.FAIL, critical = false, note = "Broken", priority = null, dueAt = null)),
            inspectorName = "Rina",
        )
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("priority"))
        assertTrue(
            CompletionRules.problems(
                listOf(ItemOutcome(ItemResult.FAIL, critical = false, note = "Broken", priority = Priority.LOW, dueAt = 1L)),
                inspectorName = "Rina",
            ).isEmpty(),
        )
    }
}
