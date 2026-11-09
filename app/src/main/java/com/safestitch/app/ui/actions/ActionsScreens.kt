package com.safestitch.app.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.ActionEventEntity
import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.Priority
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.DueDateButton
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.PrioritySelector
import com.safestitch.app.ui.components.SelectField
import com.safestitch.app.ui.components.SelectOption
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.actionStatusColor
import com.safestitch.app.ui.components.formatDate
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.components.priorityColor
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActionFilter(val label: String) {
    TODO("To do"),
    OPEN("Open"),
    IN_PROGRESS("In progress"),
    OVERDUE("Overdue"),
    AWAITING("Awaiting verification"),
    VERIFIED("Verified"),
    ALL("All"),
    ;

    fun matches(row: ActionRow, now: Long): Boolean = when (this) {
        TODO -> row.action.status.isActive
        OPEN -> row.action.status == ActionStatus.OPEN
        IN_PROGRESS -> row.action.status == ActionStatus.IN_PROGRESS
        OVERDUE -> FindingDefaults.isOverdue(row.action.status, row.action.dueAt, now)
        AWAITING -> row.action.status == ActionStatus.RESOLVED
        VERIFIED -> row.action.status == ActionStatus.VERIFIED
        ALL -> true
    }
}

class ActionsViewModel(repository: InspectionRepository) : ViewModel() {
    val rows: StateFlow<List<ActionRow>?> = repository.observeActionRows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var filter by mutableStateOf(ActionFilter.TODO)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    val vm = viewModel { ActionsViewModel(container.repository) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { TopAppBar(title = { Text("Corrective actions") }) },
    ) { padding ->
        val all = rows.orEmpty()
        val shown = all.filter { vm.filter.matches(it, now) }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("actions-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ActionFilter.entries) { f ->
                        FilterChip(
                            selected = vm.filter == f,
                            onClick = { vm.filter = f },
                            label = { Text("${f.label} (${all.count { f.matches(it, now) }})") },
                            modifier = Modifier.testTag("filter-${f.name}"),
                        )
                    }
                }
            }
            item {
                Text(
                    "${shown.size} shown · ${all.size} corrective action${if (all.size == 1) "" else "s"} in total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("actions-count"),
                )
            }
            if (rows != null && all.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text("No corrective actions yet. Every failed checklist item creates one when the inspection is submitted.", modifier = Modifier.padding(20.dp))
                    }
                }
            }
            items(shown, key = { it.action.id }) { row -> ActionCard(row, now, onClick = { onOpen(row.action.id) }) }
        }
    }
}

@Composable
private fun ActionCard(row: ActionRow, now: Long, onClick: () -> Unit) {
    val a = row.action
    val overdue = FindingDefaults.isOverdue(a.status, a.dueAt, now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("action-${a.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(a.priority.label, priorityColor(a.priority))
                StatusChip(a.status.label, actionStatusColor(a.status))
                if (overdue) StatusChip("Overdue", LocalStatusColors.current.fail)
            }
            Text(row.question, style = MaterialTheme.typography.titleSmall)
            if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${row.checkpointName} (${row.checkpointCode})" + if (row.locationSnapshot.isNotBlank()) "\n${row.locationSnapshot}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Responsible: ${a.responsibleName.ifBlank { "—" }}  ·  Due ${formatDate(a.dueAt)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                color = if (overdue) LocalStatusColors.current.fail else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

class ActionDetailViewModel(actionId: Long, private val repository: InspectionRepository) : ViewModel() {
    val row: StateFlow<ActionRow?> = repository.observeActionRow(actionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events: StateFlow<List<ActionEventEntity>> = repository.observeActionEvents(actionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    fun run(success: String, block: suspend () -> Boolean) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            message = try {
                if (block()) success else "This action changed in the meantime; nothing was saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Could not save."
            } finally {
                busy = false
            }
        }
    }

    fun photos(itemId: Long) = repository.observePhotosForItem(itemId)
}

private fun eventText(e: ActionEventEntity): String {
    val what = when {
        e.fromStatus == null -> "Created (Open)"
        e.fromStatus == e.toStatus -> "Updated"
        e.fromStatus == ActionStatus.RESOLVED && e.toStatus == ActionStatus.IN_PROGRESS -> "Sent back: ${ActionStatus.RESOLVED.label} → ${e.toStatus.label}"
        else -> "${e.fromStatus.label} → ${e.toStatus.label}"
    }
    return "$what · ${e.byName} · ${formatDateTime(e.at)}" + if (e.note.isNotBlank()) "\n    ${e.note}" else ""
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ActionDetailScreen(
    container: AppContainer,
    user: UserEntity,
    actionId: Long,
    onBack: () -> Unit,
    onOpenInspection: (Long) -> Unit,
    onAddAfterFixPhoto: (inspectionId: Long, actionId: Long) -> Unit,
) {
    val vm = viewModel { ActionDetailViewModel(actionId, container.repository) }
    val row by vm.row.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val users by remember { container.auth.observeUsers() }.collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    var resolution by rememberSaveable { mutableStateOf("") }
    var sendBackOpen by remember { mutableStateOf(false) }
    var sendBackComment by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Corrective action") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = row
        if (r == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val a = r.action
        val now = System.currentTimeMillis()
        val photos by remember(a.itemId) { vm.photos(a.itemId) }.collectAsStateWithLifecycle(emptyList())
        val evidence = photos.filter { it.actionId == null }
        val afterFix = photos.filter { it.actionId == a.id }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("action-detail"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(a.priority.label, priorityColor(a.priority))
                StatusChip(a.status.label, actionStatusColor(a.status), Modifier.testTag("action-status"))
                if (FindingDefaults.isOverdue(a.status, a.dueAt, now)) StatusChip("Overdue", LocalStatusColors.current.fail)
            }
            Text("${r.itemPosition + 1}. ${r.question}", style = MaterialTheme.typography.titleMedium)
            Text("Finding: ${a.description.ifBlank { "—" }}", style = MaterialTheme.typography.bodyLarge)
            Text("Responsible: ${a.responsibleName.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("action-responsible"))
            Text("Due ${formatDate(a.dueAt)}", style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.checkpointName} (${r.checkpointCode})", style = MaterialTheme.typography.titleSmall)
                    Text(listOf(r.locationSnapshot, r.spot).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "Location not set" }, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenInspection(a.inspectionId) }, modifier = Modifier.testTag("open-inspection")) { Text("Open inspection report") }
                }
            }
            if (evidence.isNotEmpty()) {
                Text("Evidence photos", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(evidence, key = { it.id }) { PhotoThumbnail(container.photoStorage.file(it.fileName), onClick = {}) }
                }
            }
            if (afterFix.isNotEmpty()) {
                Text("After-fix photos (${afterFix.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("after-fix-count"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(afterFix, key = { it.id }) { PhotoThumbnail(container.photoStorage.file(it.fileName), onClick = {}) }
                }
            }
            a.adminComment?.takeIf { it.isNotBlank() && a.status != ActionStatus.VERIFIED }?.let {
                Text("Admin's note: $it", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.fail)
            }

            when (a.status) {
                ActionStatus.OPEN -> Button(
                    onClick = { vm.run("Work started (In progress).") { container.repository.startAction(a.id, user) } },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().testTag("start-action"),
                ) { Text("Start work") }
                ActionStatus.IN_PROGRESS -> {
                    OutlinedButton(onClick = { onAddAfterFixPhoto(a.inspectionId, a.id) }, modifier = Modifier.fillMaxWidth().testTag("after-fix-photo")) {
                        Text(if (afterFix.isEmpty()) "Add after-fix photo (optional)" else "Add another after-fix photo")
                    }
                    OutlinedTextField(
                        value = resolution,
                        onValueChange = { resolution = it.take(1_000) },
                        label = { Text("What was done to fix it?") },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("resolution-note"),
                    )
                    Button(
                        onClick = { vm.run("Marked as resolved. An admin will verify it.") { container.repository.resolveAction(a.id, resolution, user) } },
                        enabled = !vm.busy && resolution.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("resolve-action"),
                    ) { Text("Mark as resolved") }
                }
                ActionStatus.RESOLVED -> if (user.isAdmin) {
                    Text("Fix reported: ${a.resolutionNote.orEmpty()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("resolution-text"))
                    Button(
                        onClick = { vm.run("Verified and closed.") { container.repository.verifyAction(a.id, user) } },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().testTag("verify-action"),
                    ) { Text("Verify & close") }
                    OutlinedButton(onClick = { sendBackOpen = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().testTag("reopen-action")) {
                        Text("Not fixed: send back")
                    }
                } else {
                    Text("Fix reported: ${a.resolutionNote.orEmpty()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("resolution-text"))
                    Text(
                        "Only an admin can verify this fix.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("verify-locked"),
                    )
                }
                ActionStatus.VERIFIED -> Text(
                    "Closed: verified by ${a.verifiedByName.orEmpty()}" + (a.verifiedAt?.let { " on ${formatDateTime(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalStatusColors.current.pass,
                    modifier = Modifier.testTag("verified-text"),
                )
            }

            Text("History", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                Text(
                    events.joinToString("\n") { "• " + eventText(it) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp).testTag("action-history"),
                )
            }

            if (user.isAdmin && a.status != ActionStatus.VERIFIED) {
                Text("Admin: responsible person, priority and due date", style = MaterialTheme.typography.titleSmall)
                SelectField(
                    label = "Responsible person",
                    selectedLabel = a.responsibleName.ifBlank { "—" },
                    options = users.filter { it.active }.map { SelectOption(it, "${it.displayName} (${it.role.label})") },
                    onSelect = { u -> vm.run("Responsible person changed.") { container.repository.reassignAction(a.id, user, u.displayName, u.id) } },
                    testTag = "action-responsible-select",
                )
                PrioritySelector(
                    selected = a.priority,
                    onSelect = { p: Priority ->
                        vm.run("Priority changed.") { container.repository.rescheduleAction(a.id, user, p, FindingDefaults.dueAt(p, System.currentTimeMillis())) }
                    },
                    tagPrefix = "action-priority",
                )
                DueDateButton(
                    dueAt = a.dueAt,
                    onPick = { due -> vm.run("Due date changed.") { container.repository.rescheduleAction(a.id, user, a.priority, due) } },
                    testTag = "action-due",
                )
            }
        }
    }

    if (sendBackOpen) {
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { sendBackOpen = false },
            title = { Text("Send back to In progress") },
            text = {
                OutlinedTextField(
                    value = sendBackComment,
                    onValueChange = { sendBackComment = it.take(500) },
                    label = { Text("Why is it not fixed?") },
                    modifier = Modifier.fillMaxWidth().testTag("reopen-comment"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sendBackOpen = false
                        val current = row ?: return@TextButton
                        vm.run("Sent back to In progress.") { container.repository.sendBackAction(current.action.id, user, sendBackComment) }
                    },
                    enabled = sendBackComment.isNotBlank(),
                    modifier = Modifier.testTag("reopen-confirm"),
                ) { Text("Send back") }
            },
            dismissButton = { TextButton(onClick = { sendBackOpen = false }) { Text("Cancel") } },
        )
    }
}
