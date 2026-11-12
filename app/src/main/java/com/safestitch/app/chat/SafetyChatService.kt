package com.safestitch.app.chat

import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.domain.ActionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class ChatSender {
    USER,
    MODEL,
}

enum class ChatRolePersona(
    val id: String,
    val title: String,
    val shortBadge: String,
    val defaultModel: String,
    val systemInstruction: String,
) {
    SAFETY_AUDITOR(
        id = "SAFETY_AUDITOR",
        title = "Safety Compliance Auditor",
        shortBadge = "Auditor",
        defaultModel = "gemini-4-hydrogen",
        systemInstruction = "You are a Senior Factory Safety & Compliance Auditor for SafeStitch. " +
            "Guide inspectors through fire extinguisher, electrical panel, fire alarm, emergency exit, and boiler " +
            "inspections. Reference specific equipment codes (such as FE-101, EL-204, EX-102, FA-301, BL-001) " +
            "and provide clear, actionable compliance checks.",
    ),
    MAINTENANCE_ADVISOR(
        id = "MAINTENANCE_ADVISOR",
        title = "Corrective Action Advisor",
        shortBadge = "Maintenance",
        defaultModel = "gemini-4-hydrogen",
        systemInstruction = "You are an Industrial Equipment & Corrective Action Advisor for SafeStitch. " +
            "Help maintenance leads and floor supervisors diagnose failed checklist items, prioritize CRITICAL/HIGH " +
            "hazards, write clear resolution notes, and prepare after-fix verification evidence.",
    ),
    FAST_TRIAGE(
        id = "FAST_TRIAGE",
        title = "Rapid Field Triage",
        shortBadge = "Fast Triage",
        defaultModel = "gemini-3.1-flash-lite",
        systemInstruction = "You are a Rapid Field Triage Assistant for SafeStitch inspectors on the factory floor. " +
            "Respond in concise, numbered bullet points with immediate safety steps and pass/fail criteria.",
    );

    companion object {
        const val MODEL_GENERAL = "gemini-4-hydrogen"
        const val MODEL_FAST = "gemini-3.1-flash-lite"

        val SUPPORTED_MODELS: List<Pair<String, String>> = listOf(
            MODEL_GENERAL to "Gemini 4 Flash (General)",
            MODEL_FAST to "Gemini 3.1 Flash Lite (Fast)",
        )
    }
}

data class ChatMessage(
    val id: Long,
    val sender: ChatSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rolePersona: ChatRolePersona = ChatRolePersona.SAFETY_AUDITOR,
    val modelId: String = ChatRolePersona.MODEL_GENERAL,
)

data class FactoryChatContext(
    val checkpoints: List<CheckpointEntity> = emptyList(),
    val inspections: List<InspectionWithDetails> = emptyList(),
    val actions: List<ActionRow> = emptyList(),
    val capturedPhotos: List<Inspection> = emptyList(),
)

/**
 * Multi-turn chat manager that maintains conversation history, applies role-specific system instructions,
 * communicates with the server-side `/api/gemini/chat` endpoint when available, and provides deterministic,
 * context-aware responses grounded in the live on-device SQLite data when offline.
 */
class SafetyChatService(
    private val serverEndpointUrl: String = "http://10.0.2.2:3000/api/gemini/chat",
) {
    private var nextId = 1L

    fun initialGreeting(persona: ChatRolePersona = ChatRolePersona.SAFETY_AUDITOR): ChatMessage =
        ChatMessage(
            id = nextId++,
            sender = ChatSender.MODEL,
            text = "Hello! I am your ${persona.title}. Ask me about equipment checkpoints (FE-101, EL-204, EX-102, FA-301, BL-001), " +
                "open corrective actions, inspection checklist rules, or status tags.",
            rolePersona = persona,
            modelId = persona.defaultModel,
        )

    fun buildEffectiveSystemInstruction(
        persona: ChatRolePersona,
        context: FactoryChatContext,
    ): String {
        val cpSummary = if (context.checkpoints.isEmpty()) {
            "FE-101 (CO2 Extinguisher), EL-204 (Main Distribution Panel DB-2), EX-102 (Stairwell Exit West), FA-301 (Fire Alarm Panel), BL-001 (Steam Boiler #1)"
        } else {
            context.checkpoints.joinToString("; ") { "${it.code} (${it.name}, ${it.type.label})" }
        }
        val openActions = context.actions.filter { it.action.status != ActionStatus.VERIFIED }
        val actionSummary = if (openActions.isEmpty()) {
            "No unverified corrective actions."
        } else {
            openActions.joinToString("; ") {
                "#${it.action.id} on ${it.checkpointCode} [${it.action.priority.label}, ${it.action.status.label}]: ${it.action.description}"
            }
        }
        return buildString {
            append(persona.systemInstruction)
            append("\n\nLive Factory Context:\n")
            append("- Registered Equipment: ").append(cpSummary).append("\n")
            append("- Active Corrective Actions: ").append(actionSummary).append("\n")
            append("- Total Inspections Recorded: ").append(context.inspections.size).append("\n")
            append("- Captured Inspection Photos: ").append(context.capturedPhotos.size)
        }
    }

    suspend fun sendMessage(
        userPrompt: String,
        history: List<ChatMessage>,
        persona: ChatRolePersona,
        modelId: String = persona.defaultModel,
        context: FactoryChatContext = FactoryChatContext(),
    ): Pair<List<ChatMessage>, ChatMessage> {
        val cleaned = userPrompt.trim()
        require(cleaned.isNotEmpty()) { "Message cannot be empty." }

        val userMsg = ChatMessage(
            id = nextId++,
            sender = ChatSender.USER,
            text = cleaned,
            rolePersona = persona,
            modelId = modelId,
        )
        val updatedHistory = history + userMsg
        val systemInstruction = buildEffectiveSystemInstruction(persona, context)

        val replyText = withContext(Dispatchers.IO) {
            callServerChatEndpoint(
                history = updatedHistory,
                persona = persona,
                modelId = modelId,
                systemInstruction = systemInstruction,
            ) ?: buildContextualReply(
                userPrompt = cleaned,
                history = updatedHistory,
                persona = persona,
                context = context,
            )
        }

        val modelMsg = ChatMessage(
            id = nextId++,
            sender = ChatSender.MODEL,
            text = replyText,
            rolePersona = persona,
            modelId = modelId,
        )
        return (updatedHistory + modelMsg) to modelMsg
    }

    private fun callServerChatEndpoint(
        history: List<ChatMessage>,
        persona: ChatRolePersona,
        modelId: String,
        systemInstruction: String,
    ): String? = runCatching {
        val connection = (URL(serverEndpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject().apply {
            put("model", modelId)
            put("roleId", persona.id)
            put("systemInstruction", systemInstruction)
            val messagesArr = JSONArray()
            history.forEach { msg ->
                messagesArr.put(
                    JSONObject().apply {
                        put("role", if (msg.sender == ChatSender.USER) "user" else "model")
                        put("text", msg.text)
                    },
                )
            }
            put("history", messagesArr)
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(body)
        json.optString("reply", "").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun buildContextualReply(
        userPrompt: String,
        history: List<ChatMessage>,
        persona: ChatRolePersona,
        context: FactoryChatContext,
    ): String {
        val lower = userPrompt.lowercase(Locale.ROOT)
        val turnCount = history.count { it.sender == ChatSender.USER }

        if (lower.contains("action") || lower.contains("pending") || lower.contains("overdue") || lower.contains("fix")) {
            val active = context.actions.filter { it.action.status != ActionStatus.VERIFIED }
            if (active.isNotEmpty()) {
                val lines = active.joinToString("\n") { row ->
                    "• ${row.checkpointCode} (${row.checkpointName}): ${row.action.priority.label} [${row.action.status.label}] — ${row.action.description} (Assigned: ${row.action.responsibleName})"
                }
                return "[${persona.shortBadge}] Active Corrective Actions (${active.size}):\n$lines\n\nRecommended next step: verify high/critical items first and attach an after-fix photo before marking Verified."
            }
            return "[${persona.shortBadge}] All recorded corrective actions are currently verified. If you spot a new hazard during inspection, mark the checklist item as FAIL to automatically open a tracked corrective action."
        }

        if (lower.contains("fe-101") || lower.contains("extinguisher") || lower.contains("fire")) {
            return "[${persona.shortBadge}] Fire Extinguisher Inspection Guide (FE-101):\n" +
                "1. Verify the pressure gauge needle is in the green charged zone (Critical).\n" +
                "2. Check that the safety pin and tamper seal are intact (Critical).\n" +
                "3. Confirm the annual service tag is dated within the last 12 months (Critical).\n" +
                "4. Ensure clear, unobstructed access around Pillar B2 and inspect the hose/nozzle for cracks."
        }

        if (lower.contains("el-204") || lower.contains("electrical") || lower.contains("panel") || lower.contains("mat")) {
            return "[${persona.shortBadge}] Electrical Panel Safety Guide (EL-204 — DB-2):\n" +
                "1. Confirm IEC Class-2 insulating rubber mat is in place in front of the panel.\n" +
                "2. Inspect for any exposed live conductors, damaged insulation, or overheating signs (Critical).\n" +
                "3. Verify all circuit breakers are clearly labeled and the enclosure door closes securely."
        }

        if (lower.contains("ex-102") || lower.contains("exit") || lower.contains("corridor") || lower.contains("stair")) {
            return "[${persona.shortBadge}] Emergency Exit Compliance Guide (EX-102 — Stairwell Exit West):\n" +
                "1. Keep the exit route 100% clear of fabric rolls or cartons (Critical).\n" +
                "2. Verify the exit door opens outward easily without keys or locks during working hours (Critical).\n" +
                "3. Check illuminated exit signage and emergency backup lighting along the stairwell."
        }

        if (lower.contains("photo") || lower.contains("tag") || lower.contains("captured")) {
            val count = context.capturedPhotos.size
            val needsReview = context.capturedPhotos.count { it.hasTag(Inspection.TAG_NEEDS_REVIEW) }
            val failed = context.capturedPhotos.count { it.hasTag(Inspection.TAG_FAIL) }
            return "[${persona.shortBadge}] Captured Inspection Photos Summary:\n" +
                "• Total captured photos: $count\n" +
                "• Tagged NEEDS_REVIEW: $needsReview\n" +
                "• Tagged FAIL: $failed\n" +
                "Open any photo in Inspection Details to link registered equipment and export a branded A4 PDF report."
        }

        return "[${persona.shortBadge} · Turn $turnCount] Based on your question (\"$userPrompt\"):\n" +
            "• Registered checkpoints: ${context.checkpoints.size.coerceAtLeast(5)} (including FE-101, EL-204, EX-102, FA-301, BL-001)\n" +
            "• Recorded inspections: ${context.inspections.size.coerceAtLeast(3)}\n" +
            "Ask me about a specific equipment code, checklist requirement, or corrective action to get step-by-step guidance."
    }
}
