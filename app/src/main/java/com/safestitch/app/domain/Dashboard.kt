package com.safestitch.app.domain

import kotlin.math.roundToInt

/** What the dashboard needs to know about one inspection. */
data class DashboardInspection(
    val completedAt: Long?,
    val outcome: Outcome,
    val scorePercent: Int,
    /** Grouping label, e.g. "Factory A › Building 1". Blank when the equipment has no location. */
    val locationGroup: String,
)

/** What the dashboard needs to know about one corrective action. */
data class DashboardAction(
    val status: ActionStatus,
    val priority: Priority,
    val dueAt: Long,
    val resolvedAt: Long?,
    val verifiedAt: Long?,
)

data class LocationStat(val label: String, val completed: Int, val passed: Int) {
    val passRatePercent: Int get() = percent(passed, completed) ?: 0
}

data class DashboardStats(
    val windowDays: Int,
    val completed: Int,
    val passed: Int,
    val failed: Int,
    /** Null when nothing was completed in the window (shown as "—", not 0%). */
    val passRatePercent: Int?,
    val averageScorePercent: Int?,
    val drafts: Int,
    val totalInspections: Int,
    val openActions: Int,
    val inProgressActions: Int,
    val overdueActions: Int,
    val awaitingVerification: Int,
    val openCriticalOrHigh: Int,
    val verifiedInWindow: Int,
    val totalActions: Int,
    /** Share of actions verified in the window that had been resolved by their due date. */
    val onTimeClosurePercent: Int?,
    /** Completed inspections in the window per location, worst pass rate first. */
    val byLocation: List<LocationStat>,
)

internal fun percent(part: Int, whole: Int): Int? = if (whole <= 0) null else (part * 100.0 / whole).roundToInt()

/**
 * Dashboard rules (unit-tested):
 *  - The window covers inspections SUBMITTED in the last [windowDays] days (drafts are counted separately).
 *  - Pass rate = passed / submitted in the window; average score = mean of their scores.
 *  - Open = OPEN; in progress = IN_PROGRESS; overdue = OPEN or IN_PROGRESS past the due date;
 *    awaiting verification = RESOLVED.
 *  - Critical/high not verified = any status except VERIFIED with priority CRITICAL or HIGH.
 *  - On-time closure = of actions verified in the window, the share resolved on or before the due date.
 */
object DashboardCalculator {
    fun compute(
        inspections: List<DashboardInspection>,
        actions: List<DashboardAction>,
        now: Long,
        windowDays: Int = 30,
    ): DashboardStats {
        val windowStart = now - windowDays * FindingDefaults.DAY_MILLIS
        val completed = inspections.filter { it.completedAt != null && it.completedAt in windowStart..now }
        val passed = completed.count { it.outcome == Outcome.PASS }
        val failed = completed.count { it.outcome == Outcome.FAIL }
        val verifiedInWindow = actions.filter { it.status == ActionStatus.VERIFIED && (it.verifiedAt ?: 0) in windowStart..now }
        val onTime = verifiedInWindow.count { it.resolvedAt != null && it.resolvedAt <= it.dueAt }
        return DashboardStats(
            windowDays = windowDays,
            completed = completed.size,
            passed = passed,
            failed = failed,
            passRatePercent = percent(passed, completed.size),
            averageScorePercent = if (completed.isEmpty()) null else completed.map { it.scorePercent }.average().roundToInt(),
            drafts = inspections.count { it.completedAt == null },
            totalInspections = inspections.size,
            openActions = actions.count { it.status == ActionStatus.OPEN },
            inProgressActions = actions.count { it.status == ActionStatus.IN_PROGRESS },
            overdueActions = actions.count { FindingDefaults.isOverdue(it.status, it.dueAt, now) },
            awaitingVerification = actions.count { it.status == ActionStatus.RESOLVED },
            openCriticalOrHigh = actions.count {
                it.status != ActionStatus.VERIFIED && (it.priority == Priority.CRITICAL || it.priority == Priority.HIGH)
            },
            verifiedInWindow = verifiedInWindow.size,
            totalActions = actions.size,
            onTimeClosurePercent = percent(onTime, verifiedInWindow.size),
            byLocation = completed
                .groupBy { it.locationGroup.ifBlank { "No location set" } }
                .map { (label, list) -> LocationStat(label, list.size, list.count { it.outcome == Outcome.PASS }) }
                .sortedWith(compareBy<LocationStat> { it.passRatePercent }.thenBy { it.label }),
        )
    }
}
