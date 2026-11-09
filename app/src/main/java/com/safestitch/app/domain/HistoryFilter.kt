package com.safestitch.app.domain

import java.util.Locale

enum class StatusFilter(val label: String) { ALL("All"), DRAFT("Drafts"), COMPLETED("Completed") }

enum class ResultFilter(val label: String) { ANY("Any result"), PASS("Passed"), FAIL("Failed") }

enum class PeriodFilter(val label: String, val days: Int?) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    ALL("All time", null),
}

/** One row of the inspection history, flattened for filtering. */
data class HistoryEntry(
    val inspectionId: Long,
    val equipmentName: String,
    val equipmentCode: String,
    val type: CheckpointType,
    val factoryId: Long?,
    val buildingId: Long?,
    val floorId: Long?,
    val areaId: Long?,
    val locationLabel: String,
    val inspectorName: String,
    val startedAt: Long,
    val completedAt: Long?,
    val summary: InspectionSummary,
)

/** History screen filters. Every criterion is optional; all set criteria must match. */
data class HistoryFilter(
    val status: StatusFilter = StatusFilter.ALL,
    val result: ResultFilter = ResultFilter.ANY,
    val period: PeriodFilter = PeriodFilter.ALL,
    val factoryId: Long? = null,
    val buildingId: Long? = null,
    val floorId: Long? = null,
    val areaId: Long? = null,
    val inspectorName: String? = null,
    val type: CheckpointType? = null,
    val query: String = "",
) {
    val isDefault: Boolean get() = this == HistoryFilter()

    fun matches(entry: HistoryEntry, now: Long): Boolean {
        val completed = entry.completedAt != null
        if (status == StatusFilter.DRAFT && completed) return false
        if (status == StatusFilter.COMPLETED && !completed) return false
        if (result == ResultFilter.PASS && !(completed && entry.summary.outcome == Outcome.PASS)) return false
        if (result == ResultFilter.FAIL && !(completed && entry.summary.outcome == Outcome.FAIL)) return false
        period.days?.let { days ->
            if ((entry.completedAt ?: entry.startedAt) < now - days * FindingDefaults.DAY_MILLIS) return false
        }
        if (factoryId != null && entry.factoryId != factoryId) return false
        if (buildingId != null && entry.buildingId != buildingId) return false
        if (floorId != null && entry.floorId != floorId) return false
        if (areaId != null && entry.areaId != areaId) return false
        if (inspectorName != null && !entry.inspectorName.equals(inspectorName, ignoreCase = true)) return false
        if (type != null && entry.type != type) return false
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isNotEmpty()) {
            val haystack = listOf(entry.equipmentName, entry.equipmentCode, entry.locationLabel, entry.inspectorName)
                .joinToString(" ").lowercase(Locale.ROOT)
            if (q !in haystack) return false
        }
        return true
    }

    fun apply(entries: List<HistoryEntry>, now: Long): List<HistoryEntry> = entries.filter { matches(it, now) }
}
