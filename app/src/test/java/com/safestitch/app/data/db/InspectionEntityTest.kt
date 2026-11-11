package com.safestitch.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionEntityTest {
    @Test
    fun inspectionEntityPreservesTimestampImagePathAndStatusTags() {
        val inspection = Inspection(
            id = 42L,
            timestamp = 1_731_234_567_890L,
            imagePath = "/data/user/0/com.safestitch.inspection.debug/files/photos/IMG_001.jpg",
            statusTags = listOf("PASS", "FOLLOW_UP"),
        )

        assertEquals(42L, inspection.id)
        assertEquals(1_731_234_567_890L, inspection.timestamp)
        assertEquals("/data/user/0/com.safestitch.inspection.debug/files/photos/IMG_001.jpg", inspection.imagePath)
        assertEquals(inspection.imagePath, inspection.imageFilePath)
        assertEquals(listOf("PASS", "FOLLOW_UP"), inspection.normalizedTags)
        assertTrue(inspection.hasTag("pass"))
        assertFalse(inspection.hasTag("FAIL"))
    }

    @Test
    fun secondaryConstructorAndConvertersParseCommaSeparatedStatusTags() {
        val inspection = Inspection(
            id = 7L,
            timestamp = 1_731_234_000_000L,
            imagePath = "/storage/emulated/0/photos/IMG_002.jpg",
            statusTags = "NEEDS_REVIEW, FAIL , NEEDS_REVIEW",
        )

        assertEquals(listOf("NEEDS_REVIEW", "FAIL"), inspection.statusTags)
        val serialized = StatusTagConverters.fromStatusTagList(inspection.statusTags)
        assertEquals("NEEDS_REVIEW,FAIL", serialized)
        assertEquals(listOf("NEEDS_REVIEW", "FAIL"), StatusTagConverters.toStatusTagList(serialized))
    }

    @Test
    fun withToggledTagAddsAndRemovesStatusTags() {
        val initial = Inspection(
            timestamp = 1_000L,
            imagePath = "/files/photos/IMG_003.jpg",
            statusTags = listOf(Inspection.TAG_NEEDS_REVIEW),
        )

        val added = initial.withToggledTag(Inspection.TAG_PASS)
        assertEquals(listOf(Inspection.TAG_NEEDS_REVIEW, Inspection.TAG_PASS), added.normalizedTags)

        val removed = added.withToggledTag(Inspection.TAG_NEEDS_REVIEW)
        assertEquals(listOf(Inspection.TAG_PASS), removed.normalizedTags)
    }

    @Test
    fun withAddedTagAndWithRemovedTagNormalizeAndMaintainFallbackTag() {
        val initial = Inspection(
            id = 9L,
            timestamp = 2_000L,
            imagePath = "/files/photos/IMG_004.jpg",
            statusTags = listOf(Inspection.TAG_PASS),
        )

        val withCustom = initial.withAddedTag("line 4 urgent")
        assertEquals(listOf("PASS", "LINE_4_URGENT"), withCustom.normalizedTags)

        val afterRemoveCustom = withCustom.withRemovedTag("line_4_urgent")
        assertEquals(listOf("PASS"), afterRemoveCustom.normalizedTags)

        val fallback = afterRemoveCustom.withRemovedTag("PASS")
        assertEquals(listOf(Inspection.TAG_NEEDS_REVIEW), fallback.normalizedTags)
    }
}
