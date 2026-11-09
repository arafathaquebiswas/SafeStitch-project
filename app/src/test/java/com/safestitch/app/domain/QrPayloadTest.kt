package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test
    fun parsesSafeStitchLabelPayload() {
        assertEquals("SS-LINE-04", QrPayload.parseCheckpointCode("SAFESTITCH:CP:SS-LINE-04"))
    }

    @Test
    fun prefixIsCaseInsensitiveAndCodeIsNormalised() {
        assertEquals("SS-LINE-04", QrPayload.parseCheckpointCode("  safestitch:cp: ss-line-04 \n"))
    }

    @Test
    fun acceptsPlainAssetTags() {
        assertEquals("FE-0012", QrPayload.parseCheckpointCode("fe-0012"))
    }

    @Test
    fun rejectsBlankTooLongAndControlCharacters() {
        assertNull(QrPayload.parseCheckpointCode("   "))
        assertNull(QrPayload.parseCheckpointCode("SAFESTITCH:CP:"))
        assertNull(QrPayload.parseCheckpointCode("A".repeat(QrPayload.MAX_CODE_LENGTH + 1)))
        assertNull(QrPayload.parseCheckpointCode("AB\u0000CD"))
    }

    @Test
    fun labelPayloadRoundTrips() {
        val code = "SS-FIRE-12"
        assertEquals(code, QrPayload.parseCheckpointCode(QrPayload.forCheckpoint(code)))
    }
}
