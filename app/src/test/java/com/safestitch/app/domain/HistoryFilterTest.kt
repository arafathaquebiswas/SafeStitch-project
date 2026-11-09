package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {
    private val day = FindingDefaults.DAY_MILLIS
    private val now = 1_800_000_000_000L

    private fun summary(outcome: Outcome) = InspectionSummary(10, if (outcome == Outcome.IN_PROGRESS) 5 else 10, 9, 1, 0, 0, 90, outcome)

    private fun entry(
        id: Long,
        outcome: Outcome,
        daysAgo: Long,
        completed: Boolean = true,
        factory: Long? = 1,
        building: Long? = 10,
        floor: Long? = 100,
        area: Long? = 1000,
        inspector: String = "Rina Akter",
        type: CheckpointType = CheckpointType.FIRE_SAFETY,
        name: String = "Extinguisher $id",
    ) = HistoryEntry(
        inspectionId = id,
        equipmentName = name,
        equipmentCode = "FE-$id",
        type = type,
        factoryId = factory,
        buildingId = building,
        floorId = floor,
        areaId = area,
        locationLabel = "Main › B1 › Floor 2 › Cutting",
        inspectorName = inspector,
        startedAt = now - daysAgo * day - 1_000,
        completedAt = if (completed) now - daysAgo * day else null,
        summary = summary(if (completed) outcome else Outcome.IN_PROGRESS),
    )

    private val entries = listOf(
        entry(1, Outcome.PASS, 1),
        entry(2, Outcome.FAIL, 5, inspector = "Karim"),
        entry(3, Outcome.PASS, 40, area = 2000, type = CheckpointType.SEWING_LINE, name = "Sewing line 4"),
        entry(4, Outcome.IN_PROGRESS, 2, completed = false),
        entry(5, Outcome.FAIL, 100, factory = 2, building = 20, floor = 200, area = 3000),
    )

    private fun ids(filter: HistoryFilter) = filter.apply(entries, now).map { it.inspectionId }

    @Test
    fun defaultFilterShowsEverything() {
        assertTrue(HistoryFilter().isDefault)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(HistoryFilter()))
    }

    @Test
    fun statusAndResult() {
        assertEquals(listOf(4L), ids(HistoryFilter(status = StatusFilter.DRAFT)))
        assertEquals(listOf(1L, 2L, 3L, 5L), ids(HistoryFilter(status = StatusFilter.COMPLETED)))
        assertEquals(listOf(2L, 5L), ids(HistoryFilter(result = ResultFilter.FAIL)))
        assertEquals(listOf(1L, 3L), ids(HistoryFilter(result = ResultFilter.PASS)))
    }

    @Test
    fun period() {
        assertEquals(listOf(1L, 2L, 4L), ids(HistoryFilter(period = PeriodFilter.WEEK)))
        assertEquals(listOf(1L, 2L, 4L), ids(HistoryFilter(period = PeriodFilter.MONTH)))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(HistoryFilter(period = PeriodFilter.QUARTER)))
    }

    @Test
    fun locationInspectorTypeAndSearch() {
        assertEquals(listOf(5L), ids(HistoryFilter(factoryId = 2)))
        assertEquals(listOf(3L), ids(HistoryFilter(areaId = 2000)))
        assertEquals(listOf(2L), ids(HistoryFilter(inspectorName = "karim")))
        assertEquals(listOf(3L), ids(HistoryFilter(type = CheckpointType.SEWING_LINE)))
        assertEquals(listOf(3L), ids(HistoryFilter(query = "sewing")))
        assertEquals(listOf(2L), ids(HistoryFilter(query = "FE-2")))
        assertEquals(listOf(2L), ids(HistoryFilter(result = ResultFilter.FAIL, period = PeriodFilter.MONTH)))
    }
}
