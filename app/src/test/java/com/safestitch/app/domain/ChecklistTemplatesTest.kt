package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTemplatesTest {
    @Test
    fun theFiveBriefTypesAreTheCurrentOptions() {
        assertEquals(
            listOf("Fire extinguisher", "Electrical panel", "Fire alarm", "Emergency exit", "Boiler"),
            CheckpointType.current.map { it.label },
        )
        assertEquals(
            listOf("Sewing line", "Fire safety point", "General work area"),
            CheckpointType.entries.filter { it.legacy }.map { it.label },
        )
    }

    @Test
    fun everyTypeHasAUsableChecklist() {
        CheckpointType.entries.forEach { type ->
            val items = ChecklistTemplates.forType(type)
            assertTrue("$type has too few items", items.size >= 6)
            assertTrue("$type has no critical item", items.any { it.critical })
            assertEquals("$type has duplicate questions", items.size, items.map { it.question }.toSet().size)
            assertTrue("$type has blank text", items.all { it.question.isNotBlank() && it.section.isNotBlank() })
        }
    }

    @Test
    fun legacyChecklistsAreUnchanged() {
        val sewing = ChecklistTemplates.forType(CheckpointType.SEWING_LINE)
        assertEquals(13, sewing.size)
        assertTrue(sewing.any { it.critical && it.question.contains("Broken-needle") })
        assertTrue(ChecklistTemplates.forType(CheckpointType.FIRE_SAFETY).any { it.section == ChecklistTemplates.WORKPLACE })
        assertEquals(10, ChecklistTemplates.forType(CheckpointType.FIRE_SAFETY).size)
        assertEquals(8, ChecklistTemplates.forType(CheckpointType.GENERAL_AREA).size)
    }
}
