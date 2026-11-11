package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ReportFileNamesTest {
    private val utc = ZoneOffset.UTC
    private val millis = LocalDateTime.of(2026, 11, 9, 14, 5).toInstant(utc).toEpochMilli()

    @Test
    fun reportNameIsStableAndSafe() {
        assertEquals("SafeStitch_SS-LINE-04_20261109-1405_R7.pdf", ReportFileNames.report("SS-LINE-04", 7, millis, utc))
    }

    @Test
    fun unsafeCharactersAreReplaced() {
        assertEquals("FE_12_Floor_2", ReportFileNames.safeSegment(" FE/12 : Floor #2 "))
        assertEquals("checkpoint", ReportFileNames.safeSegment("///"))
    }

    @Test
    fun labelSheetName() {
        assertEquals("SafeStitch_QR_labels_20261109-1405.pdf", ReportFileNames.labels(millis, utc))
    }

    @Test
    fun capturedInspectionName() {
        assertEquals("SafeStitch_Captured_20261109-1405_C12.pdf", ReportFileNames.capturedInspection(12, millis, utc))
    }
}
