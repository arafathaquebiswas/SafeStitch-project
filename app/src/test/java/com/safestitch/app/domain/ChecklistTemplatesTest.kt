package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTemplatesTest {
    @Test
    fun everyCheckpointTypeHasAUsableChecklist() {
        CheckpointType.entries.forEach { type ->
            val items = ChecklistTemplates.forType(type)
            assertTrue("$type has too few items", items.size >= 8)
            assertTrue("$type has no critical item", items.any { it.critical })
            assertEquals("$type has duplicate questions", items.size, items.map { it.question }.toSet().size)
            assertTrue("$type misses workplace safety", items.any { it.section == ChecklistTemplates.WORKPLACE })
            assertTrue("$type has blank text", items.all { it.question.isNotBlank() && it.section.isNotBlank() })
        }
    }

    @Test
    fun sewingLineIncludesStitchQualityAndNeedleControl() {
        val items = ChecklistTemplates.forType(CheckpointType.SEWING_LINE)
        assertTrue(items.any { it.section == ChecklistTemplates.STITCH })
        assertTrue(items.any { it.critical && it.question.contains("Broken-needle") })
    }
}
