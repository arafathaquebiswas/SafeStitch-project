package com.safestitch.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReportFileNames {
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

    /** Keeps only characters that are safe in file names on every platform. */
    fun safeSegment(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "checkpoint" }

    fun report(
        checkpointCode: String,
        inspectionId: Long,
        completedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "SafeStitch_${safeSegment(checkpointCode)}_${stamp(completedAtMillis, zone)}_R$inspectionId.pdf"

    fun labels(createdAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "SafeStitch_QR_labels_${stamp(createdAtMillis, zone)}.pdf"

    private fun stamp(millis: Long, zone: ZoneId): String =
        stampFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
}
