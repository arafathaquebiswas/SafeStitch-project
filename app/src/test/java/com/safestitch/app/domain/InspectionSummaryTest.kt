package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionSummaryTest {
    private fun item(result: ItemResult, critical: Boolean = false, note: String = "") = ItemOutcome(result, critical, note)

    @Test
    fun unansweredItemsKeepInspectionInProgress() {
        val summary = InspectionSummary.of(listOf(item(ItemResult.PASS), item(ItemResult.UNANSWERED)))
        assertEquals(Outcome.IN_PROGRESS, summary.outcome)
        assertEquals(1, summary.answered)
        assertEquals(1, summary.unanswered)
    }

    @Test
    fun allPassIsPass() {
        val summary = InspectionSummary.of(List(5) { item(ItemResult.PASS) })
        assertEquals(Outcome.PASS, summary.outcome)
        assertEquals(100, summary.scorePercent)
    }

    @Test
    fun scoreAtPassMarkPassesAndJustBelowFails() {
        val atMark = InspectionSummary.of(List(4) { item(ItemResult.PASS) } + item(ItemResult.FAIL, note = "x"))
        assertEquals(80, atMark.scorePercent)
        assertEquals(Outcome.PASS, atMark.outcome)

        val below = InspectionSummary.of(List(3) { item(ItemResult.PASS) } + item(ItemResult.FAIL, note = "x"))
        assertEquals(75, below.scorePercent)
        assertEquals(Outcome.FAIL, below.outcome)
    }

    @Test
    fun anyCriticalFailureFailsDespiteHighScore() {
        val summary = InspectionSummary.of(List(19) { item(ItemResult.PASS) } + item(ItemResult.FAIL, critical = true, note = "x"))
        assertEquals(95, summary.scorePercent)
        assertEquals(1, summary.criticalFailures)
        assertEquals(Outcome.FAIL, summary.outcome)
    }

    @Test
    fun notApplicableIsExcludedFromScore() {
        val summary = InspectionSummary.of(listOf(item(ItemResult.PASS), item(ItemResult.NOT_APPLICABLE), item(ItemResult.NOT_APPLICABLE)))
        assertEquals(100, summary.scorePercent)
        assertEquals(2, summary.notApplicable)
        assertEquals(Outcome.PASS, summary.outcome)
    }

    @Test
    fun completionRulesListEveryProblem() {
        val problems = CompletionRules.problems(
            listOf(item(ItemResult.UNANSWERED), item(ItemResult.FAIL), item(ItemResult.PASS)),
            inspectorName = " ",
        )
        assertEquals(4, problems.size)
        assertTrue(problems[0].contains("inspector"))
        assertTrue(problems[1].contains("1 checklist item"))
        assertTrue(problems[2].contains("1 failed item"))
        assertTrue(problems[3].contains("priority and a due date"))
    }

    @Test
    fun completionRulesPassWhenEverythingIsFilledIn() {
        val failed = ItemOutcome(ItemResult.FAIL, critical = false, note = "Guard missing; maintenance called", priority = Priority.HIGH, dueAt = 1L)
        val problems = CompletionRules.problems(
            listOf(item(ItemResult.PASS), failed, item(ItemResult.NOT_APPLICABLE)),
            inspectorName = "R. Ahmed",
        )
        assertTrue(problems.isEmpty())
    }
}
