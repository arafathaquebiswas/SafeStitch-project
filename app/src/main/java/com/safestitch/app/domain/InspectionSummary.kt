package com.safestitch.app.domain

/** The parts of a checklist answer that scoring and validation need. */
data class ItemOutcome(
    val result: ItemResult,
    val critical: Boolean,
    val note: String,
)

enum class Outcome(val label: String) {
    IN_PROGRESS("In progress"),
    PASS("PASS"),
    FAIL("FAIL"),
}

/**
 * Scoring rules:
 *  - score = passed / (passed + failed), rounded down; N/A items are not scored.
 *  - The inspection FAILS if any critical item failed, or the score is below [PASS_MARK_PERCENT].
 *  - It stays IN_PROGRESS until every item has an answer.
 */
data class InspectionSummary(
    val total: Int,
    val answered: Int,
    val passed: Int,
    val failed: Int,
    val notApplicable: Int,
    val criticalFailures: Int,
    val scorePercent: Int,
    val outcome: Outcome,
) {
    val unanswered: Int get() = total - answered

    companion object {
        const val PASS_MARK_PERCENT = 80

        fun of(items: List<ItemOutcome>): InspectionSummary {
            val passed = items.count { it.result == ItemResult.PASS }
            val failed = items.count { it.result == ItemResult.FAIL }
            val notApplicable = items.count { it.result == ItemResult.NOT_APPLICABLE }
            val answered = passed + failed + notApplicable
            val criticalFailures = items.count { it.critical && it.result == ItemResult.FAIL }
            val scored = passed + failed
            val score = if (scored == 0) 100 else passed * 100 / scored
            val outcome = when {
                items.isEmpty() || answered < items.size -> Outcome.IN_PROGRESS
                criticalFailures > 0 || score < PASS_MARK_PERCENT -> Outcome.FAIL
                else -> Outcome.PASS
            }
            return InspectionSummary(
                total = items.size,
                answered = answered,
                passed = passed,
                failed = failed,
                notApplicable = notApplicable,
                criticalFailures = criticalFailures,
                scorePercent = score,
                outcome = outcome,
            )
        }
    }
}

/** What must be true before an inspection can be completed and turned into a PDF. */
object CompletionRules {
    fun problems(items: List<ItemOutcome>, inspectorName: String): List<String> = buildList {
        if (inspectorName.isBlank()) add("Enter the inspector's name at the top of the checklist.")
        val unanswered = items.count { it.result == ItemResult.UNANSWERED }
        if (unanswered > 0) {
            add(if (unanswered == 1) "1 checklist item still needs an answer." else "$unanswered checklist items still need an answer.")
        }
        val failedWithoutNote = items.count { it.result == ItemResult.FAIL && it.note.isBlank() }
        if (failedWithoutNote > 0) {
            add(
                if (failedWithoutNote == 1) {
                    "1 failed item needs a note describing the problem and the corrective action."
                } else {
                    "$failedWithoutNote failed items need a note describing the problem and the corrective action."
                },
            )
        }
    }
}
