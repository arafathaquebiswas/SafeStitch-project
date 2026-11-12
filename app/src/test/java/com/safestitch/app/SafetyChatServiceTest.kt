package com.safestitch.app

import com.safestitch.app.chat.ChatRolePersona
import com.safestitch.app.chat.ChatSender
import com.safestitch.app.chat.FactoryChatContext
import com.safestitch.app.chat.SafetyChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyChatServiceTest {

    @Test
    fun buildEffectiveSystemInstruction_includesPersonaAndFactoryContext() {
        val service = SafetyChatService("http://127.0.0.1:9/unreachable")
        val instruction = service.buildEffectiveSystemInstruction(
            persona = ChatRolePersona.SAFETY_AUDITOR,
            context = FactoryChatContext(),
        )
        assertTrue(instruction.contains("Senior Factory Safety & Compliance Auditor"))
        assertTrue(instruction.contains("FE-101"))
        assertTrue(instruction.contains("Live Factory Context:"))
    }

    @Test
    fun buildContextualReply_maintainsTurnHistoryAndAnswersEquipmentQuestions() {
        val service = SafetyChatService("http://127.0.0.1:9/unreachable")
        val initial = service.initialGreeting(ChatRolePersona.SAFETY_AUDITOR)
        assertEquals(ChatSender.MODEL, initial.sender)

        val feReply = service.buildContextualReply(
            userPrompt = "What should I check on FE-101 extinguisher?",
            history = listOf(initial),
            persona = ChatRolePersona.SAFETY_AUDITOR,
            context = FactoryChatContext(),
        )
        assertTrue(feReply.contains("FE-101"))
        assertTrue(feReply.contains("pressure gauge"))

        val elReply = service.buildContextualReply(
            userPrompt = "Tell me about electrical panel EL-204",
            history = listOf(initial),
            persona = ChatRolePersona.MAINTENANCE_ADVISOR,
            context = FactoryChatContext(),
        )
        assertTrue(elReply.contains("EL-204"))
        assertTrue(elReply.contains("insulating rubber mat"))
    }
}
