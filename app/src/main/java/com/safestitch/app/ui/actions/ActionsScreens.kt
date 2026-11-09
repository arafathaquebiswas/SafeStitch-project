package com.safestitch.app.ui.actions

import androidx.compose.foundation.horizontalScroll
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
import com.safestitch.app.data.db.ActionRow
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.Priority
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.DueDateButton
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.PrioritySelector
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
    OPEN("Open"),
    OVERDUE("Overdue"),
    AWAITING("Awaiting verification"),
    VERIFIED("Verified"),
    ALL("All"),
    ;

    fun matches(row: ActionRow, now: Long): Boolean = when (this) {
        OPEN -> row.action.status == ActionStatus.OPEN
        OVERDUE -> FindingDefaults.isOverdue(row.action.status, row.action.dueAt, now)
        AWAITING -> row.action.status == ActionStatus.RESOLVED
        VERIFIED -> row.action.status == ActionStatus.VERIFIED
        ALL -> true
    }
}

class ActionsViewModel(repository: InspectionRepository) : ViewModel() {
    val rows: StateFlow<List<ActionRow>?> = repository.observeActionRows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var filter by mutableStateOf(ActionFilter.OPEN)
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
                    "${shown.size} corrective action${if (shown.size == 1) "" else "s"} · ${all.size} in total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("actions-count"),
                )
            }
            if (rows != null && all.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No corrective actions yet. Every failed checklist item creates one when the inspection is submitted.",
                            modifier = Modifier.padding(20.dp),
                        )
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
                "Due ${formatDate(a.dueAt)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                color = if (overdue) LocalStatusColors.current.fail else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

class ActionDetailViewModel(actionId: Long, private val repository: InspectionRepository) : ViewModel() {
    val row: StateFlow<ActionRow?> = repository.observeActionRow(actionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ActionDetailScreen(container: AppContainer, user: UserEntity, actionId: Long, onBack: () -> Unit, onOpenInspection: (Long) -> Unit) {
    val vm = viewModel { ActionDetailViewModel(actionId, container.repository) }
    val row by vm.row.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var resolution by rememberSaveable { mutableStateOf("") }
    var reopenOpen by remember { mutableStateOf(false) }
    var reopenComment by rememberSaveable { mutableStateOf("") }

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
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(a.priority.label, priorityColor(a.priority))
                StatusChip(a.status.label, actionStatusColor(a.status), Modifier.testTag("action-status"))
                if (FindingDefaults.isOverdue(a.status, a.dueAt, now)) StatusChip("Overdue", LocalStatusColors.current.fail)
            }
            Text("${r.itemPosition + 1}. ${r.question}", style = MaterialTheme.typography.titleMedium)
            Text("Finding: ${a.description.ifBlank { "—" }}", style = MaterialTheme.typography.bodyLarge)
            Text("Due ${formatDate(a.dueAt)}", style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.checkpointName} (${r.checkpointCode})", style = MaterialTheme.typography.titleSmall)
                    Text(listOf(r.locationSnapshot, r.spot).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "Location not set" }, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Found by ${r.inspectorName}" + (r.inspectionCompletedAt?.let { " on ${formatDateTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onOpenInspection(a.inspectionId) }, modifier = Modifier.testTag("open-inspection")) { Text("Open inspection report") }
                }
            }
            if (photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.id }) { PhotoThumbnail(container.photoStorage.file(it.fileName), onClick = {}) }
                }
            }
            a.adminComment?.takeIf { it.isNotBlank() }?.let {
                Text("Admin's note: $it", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.fail)
            }
            if (a.resolvedAt != null || a.status != ActionStatus.OPEN) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Fix reported", style = MaterialTheme.typography.titleSmall)
                        Text(a.resolutionNote.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("resolution-text"))
                        Text(
                            "by ${a.resolvedByName.orEmpty()}" + (a.resolvedAt?.let { " · ${formatDateTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (a.status == ActionStatus.VERIFIED) {
                            Text(
                                "Verified by ${a.verifiedByName.orEmpty()}" + (a.verifiedAt?.let { " · ${formatDateTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.pass,
                                modifier = Modifier.testTag("verified-text"),
                            )
                        }
                    }
                }
            }
            when (a.status) {
                ActionStatus.OPEN -> {
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
                    Button(
                        onClick = { vm.run("Verified and closed.") { container.repository.verifyAction(a.id, user) } },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().testTag("verify-action"),
                    ) { Text("Verify & close") }
                    OutlinedButton(onClick = { reopenOpen = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().testTag("reopen-action")) {
                        Text("Not fixed: reopen")
                    }
                } else {
                    Text("Waiting for an admin to verify the fix.", style = MaterialTheme.typography.bodyMedium)
                }
                ActionStatus.VERIFIED -> Text("Closed.", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.pass)
            }
            if (user.isAdmin && a.status != ActionStatus.VERIFIED) {
                Text("Admin: priority and due date", style = MaterialTheme.typography.titleSmall)
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

    if (reopenOpen) {
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { reopenOpen = false },
            title = { Text("Reopen corrective action") },
            text = {
                OutlinedTextField(
                    value = reopenComment,
                    onValueChange = { reopenComment = it.take(500) },
                    label = { Text("Why is it not fixed?") },
                    modifier = Modifier.fillMaxWidth().testTag("reopen-comment"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        reopenOpen = false
                        val r = row ?: return@TextButton
                        vm.run("Reopened.") { container.repository.reopenAction(r.action.id, user, reopenComment) }
                    },
                    enabled = reopenComment.isNotBlank(),
                    modifier = Modifier.testTag("reopen-confirm"),
                ) { Text("Reopen") }
            },
            dismissButton = { TextButton(onClick = { reopenOpen = false }) { Text("Cancel") } },
        )
    }
}
