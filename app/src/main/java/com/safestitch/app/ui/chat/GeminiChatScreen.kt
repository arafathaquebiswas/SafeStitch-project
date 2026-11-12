package com.safestitch.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.AppContainer
import com.safestitch.app.audio.AudioTranscriptionService
import com.safestitch.app.chat.ChatMessage
import com.safestitch.app.chat.ChatRolePersona
import com.safestitch.app.chat.ChatSender
import com.safestitch.app.chat.FactoryChatContext
import com.safestitch.app.chat.SafetyChatService
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.formatDateTime
import kotlinx.coroutines.launch

private val QUICK_PROMPTS = listOf(
    "Summarize open corrective actions",
    "What should I check on FE-101 extinguisher?",
    "Checklist rules for Electrical Panel EL-204",
    "How do I resolve EX-102 exit obstruction?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiChatScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val checkpoints by remember { container.repository.observeCheckpoints() }.collectAsStateWithLifecycle(emptyList())
    val inspections by remember { container.repository.observeInspections() }.collectAsStateWithLifecycle(emptyList())
    val actions by remember { container.repository.observeActionRows() }.collectAsStateWithLifecycle(emptyList())
    val capturedPhotos by remember { container.repository.observeCapturedInspections() }.collectAsStateWithLifecycle(emptyList())

    val chatService = remember { SafetyChatService() }
    val transcribeService = remember { AudioTranscriptionService() }
    var selectedPersona by remember { mutableStateOf(ChatRolePersona.SAFETY_AUDITOR) }
    var selectedModel by rememberSaveable { mutableStateOf(ChatRolePersona.MODEL_GENERAL) }
    var messages by remember {
        mutableStateOf(listOf(chatService.initialGreeting(ChatRolePersona.SAFETY_AUDITOR)))
    }
    var draftInput by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var isTranscribingAudio by remember { mutableStateOf(false) }
    var lastTranscriptionStatus by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun toggleMicRecording() {
        if (isTranscribingAudio) return
        if (!isRecordingAudio) {
            transcribeService.startMicrophoneRecording()
            isRecordingAudio = true
            lastTranscriptionStatus = "Recording microphone audio… Tap Stop to transcribe with ${AudioTranscriptionService.MODEL_TRANSCRIBE}."
        } else {
            isRecordingAudio = false
            isTranscribingAudio = true
            lastTranscriptionStatus = "Transcribing audio with ${AudioTranscriptionService.MODEL_TRANSCRIBE}…"
            scope.launch {
                val result = transcribeService.stopAndTranscribe(
                    contextHint = checkpoints.firstOrNull()?.code.orEmpty(),
                )
                isTranscribingAudio = false
                draftInput = if (draftInput.isBlank()) result.text else "$draftInput ${result.text}"
                lastTranscriptionStatus = "Transcribed (${result.model}): \"${result.text}\""
            }
        }
    }

    fun sendPrompt(promptText: String) {
        val trimmed = promptText.trim()
        if (trimmed.isEmpty() || sending) return
        draftInput = ""
        sending = true
        val context = FactoryChatContext(
            checkpoints = checkpoints,
            inspections = inspections,
            actions = actions,
            capturedPhotos = capturedPhotos,
        )
        scope.launch {
            val (updatedHistory, _) = chatService.sendMessage(
                userPrompt = trimmed,
                history = messages,
                persona = selectedPersona,
                modelId = selectedModel,
                context = context,
            )
            messages = updatedHistory
            sending = false
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safety Assistant Chat") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(
                        onClick = {
                            messages = listOf(chatService.initialGreeting(selectedPersona))
                        },
                        modifier = Modifier.testTag("chat-clear"),
                    ) {
                        Text("Clear")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .testTag("gemini-chat-screen"),
        ) {
            // Persona & Model Configuration Header
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("chat-role-card"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Specialist Role & System Instruction",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatRolePersona.entries.forEach { persona ->
                            FilterChip(
                                selected = selectedPersona == persona,
                                onClick = {
                                    selectedPersona = persona
                                    selectedModel = persona.defaultModel
                                },
                                label = { Text(persona.title) },
                                modifier = Modifier.testTag("chat-role-${persona.id}"),
                            )
                        }
                    }
                    Text(
                        text = selectedPersona.systemInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat-system-instruction"),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Model:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ChatRolePersona.SUPPORTED_MODELS.forEach { (modelId, label) ->
                            FilterChip(
                                selected = selectedModel == modelId,
                                onClick = { selectedModel = modelId },
                                label = { Text(label) },
                                modifier = Modifier.testTag("chat-model-$modelId"),
                            )
                        }
                    }
                }
            }

            // Quick Prompt Suggestions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("chat-quick-prompts"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_PROMPTS.forEachIndexed { index, prompt ->
                    AssistChip(
                        onClick = { sendPrompt(prompt) },
                        label = { Text(prompt) },
                        modifier = Modifier.testTag("chat-quick-$index"),
                    )
                }
            }

            // Scrollable Multi-Turn Conversation Thread
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat-message-list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(message = msg)
                }
            }

            // Message Input Bar with Microphone Audio Transcription (gemini-3.5-transcribe)
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lastTranscriptionStatus?.let { statusText ->
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("chat-transcribe-status"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !isTranscribingAudio && !sending,
                            onClick = ::toggleMicRecording,
                            modifier = Modifier.testTag("chat-mic-record"),
                        ) {
                            Text(
                                when {
                                    isRecordingAudio -> "Stop Mic"
                                    isTranscribingAudio -> "…"
                                    else -> "Mic"
                                },
                            )
                        }
                        OutlinedTextField(
                            value = draftInput,
                            onValueChange = { draftInput = it },
                            placeholder = { Text("Ask or use Mic (gemini-3.5-transcribe)…") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat-input"),
                        )
                        Button(
                            enabled = draftInput.isNotBlank() && !sending && !isRecordingAudio,
                            onClick = { sendPrompt(draftInput) },
                            modifier = Modifier.testTag("chat-send"),
                        ) {
                            Text(if (sending) "…" else "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    val isUser = message.sender == ChatSender.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat-msg-${message.id}"),
        horizontalAlignment = alignment,
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isUser) "You" else "${message.rolePersona.title} (${message.modelId})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatDateTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
