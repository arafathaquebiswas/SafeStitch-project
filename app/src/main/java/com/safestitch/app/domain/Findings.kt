package com.safestitch.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Defaults for a finding (a failed checklist item): critical items start as CRITICAL,
 * everything else as MEDIUM; the due date follows the priority and ends at 23:59 local time.
 */
object FindingDefaults {
    const val DAY_MILLIS = 86_400_000L

    fun priorityFor(criticalItem: Boolean): Priority = if (criticalItem) Priority.CRITICAL else Priority.MEDIUM

    fun dueAt(priority: Priority, fromMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        endOfDay(Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().plusDays(priority.defaultDueDays.toLong()), zone)

    fun endOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli()

    /** Open actions past their due date. Resolved actions are waiting for the admin, not overdue. */
    fun isOverdue(status: ActionStatus, dueAt: Long, now: Long): Boolean = status == ActionStatus.OPEN && dueAt < now
}
