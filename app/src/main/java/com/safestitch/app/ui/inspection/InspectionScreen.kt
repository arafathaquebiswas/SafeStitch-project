package com.safestitch.app.ui.inspection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.data.db.ChecklistItemEntity
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.data.db.PhotoEntity
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.DueDateButton
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.PrioritySelector
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.components.resultColor
import com.safestitch.app.ui.theme.LocalStatusColors

private val answerOptions = listOf(ItemResult.PASS, ItemResult.FAIL, ItemResult.NOT_APPLICABLE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionScreen(
    container: AppContainer,
    inspectionId: Long,
    onBack: () -> Unit,
    onTakePhoto: (itemId: Long?) -> Unit,
    onCompleted: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm = viewModel { InspectionViewModel(inspectionId, container.repository, container.reportService) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<PhotoEntity?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                InspectionViewModel.Event.Completed -> onCompleted()
                InspectionViewModel.Event.Deleted -> onDeleted()
                is InspectionViewModel.Event.Error -> snackbar.showSnackbar(it.message)
            }
        }
    }

    val current = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current?.details?.checkpoint?.name ?: "Inspection", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        current?.details?.checkpoint?.let {
                            Text("${it.code}  ·  ${it.type.label}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete-inspection")) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete inspection")
                    }
                },
            )
        },
        bottomBar = { if (current != null) SubmitBar(current.summary, vm.completing, vm::complete) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().testTag("inspection-list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { HeaderCard(current.details) }
            item(key = "progress") { ProgressCard(current.summary) }
            current.sections.forEach { (section, items) ->
                item(key = "section-$section") {
                    Text(section, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(items, key = { it.id }) { item ->
                    ChecklistItemCard(
                        item = item,
                        photos = current.photosByItem[item.id].orEmpty(),
                        photoStorage = container.photoStorage,
                        vm = vm,
                        onTakePhoto = { onTakePhoto(item.id) },
                        onPhotoClick = { photoToDelete = it },
                    )
                }
            }
            item(key = "general") {
                GeneralCard(
                    details = current.details,
                    photos = current.photosByItem[null].orEmpty(),
                    photoStorage = container.photoStorage,
                    onNotes = vm::setGeneralNotes,
                    onTakePhoto = { onTakePhoto(null) },
                    onPhotoClick = { photoToDelete = it },
                )
            }
        }
    }

    if (vm.completionProblems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissProblems,
            title = { Text("Almost done") },
            text = { Text(vm.completionProblems.joinToString("\n\n") { "• $it" }) },
            confirmButton = { TextButton(onClick = vm::dismissProblems) { Text("OK") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this inspection?") },
            text = { Text("All answers, notes and photos for this inspection will be removed from the phone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    photoToDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            title = { Text("Remove photo?") },
            text = { Text("The photo will not appear in the PDF report.") },
            confirmButton = { TextButton(onClick = { photoToDelete = null; vm.deletePhoto(photo.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { photoToDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun HeaderCard(details: InspectionWithDetails) {
    val inspection = details.inspection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Inspector: ${inspection.inspectorName}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("inspector-name"))
            Text(
                "Location: " + listOf(inspection.siteName.ifBlank { "not set" }, details.checkpoint.area).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Started ${formatDateTime(inspection.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressCard(summary: InspectionSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${summary.answered} of ${summary.total} answered", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("progress-text"))
            LinearProgressIndicator(
                progress = { if (summary.total == 0) 0f else summary.answered.toFloat() / summary.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("${summary.passed} pass", LocalStatusColors.current.pass)
                StatusChip("${summary.failed} fail", LocalStatusColors.current.fail)
                StatusChip("${summary.notApplicable} N/A", LocalStatusColors.current.neutral)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistItemCard(
    item: ChecklistItemEntity,
    photos: List<PhotoEntity>,
    photoStorage: PhotoStorage,
    vm: InspectionViewModel,
    onTakePhoto: () -> Unit,
    onPhotoClick: (PhotoEntity) -> Unit,
) {
    var note by rememberSaveable(item.id) { mutableStateOf(item.note) }
    var noteOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val failed = item.result == ItemResult.FAIL
    val tag = "item-${item.position}"
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text("${item.position + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.question, style = MaterialTheme.typography.bodyLarge)
                    if (item.critical) Text("Critical item", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.fail)
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                answerOptions.forEachIndexed { index, option ->
                    val color = resultColor(option)
                    SegmentedButton(
                        selected = item.result == option,
                        onClick = { vm.setResult(item, if (item.result == option) ItemResult.UNANSWERED else option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = answerOptions.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = color.copy(alpha = 0.18f), activeContentColor = color),
                        modifier = Modifier.testTag("$tag-${option.name}"),
                    ) { Text(option.label) }
                }
            }
            if (failed || noteOpen || note.isNotEmpty()) {
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        note = it.take(1_000)
                        vm.setNote(item.id, note)
                    },
                    label = { Text(if (failed) "Problem and corrective action (required)" else "Note") },
                    isError = failed && note.isBlank(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("$tag-note"),
                )
            }
            if (failed) {
                Text("Finding priority", style = MaterialTheme.typography.labelLarge)
                PrioritySelector(selected = item.priority, onSelect = { vm.setPriority(item.id, it) }, tagPrefix = "$tag-priority")
                DueDateButton(dueAt = item.dueAt, onPick = { vm.setDueDate(item, it) }, testTag = "$tag-due")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTakePhoto, modifier = Modifier.testTag("$tag-photo")) {
                    Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (photos.isEmpty()) "Add photo" else "Photo (${photos.size})")
                }
                if (!failed && !noteOpen && note.isEmpty()) TextButton(onClick = { noteOpen = true }) { Text("Add note") }
            }
            PhotoStrip(photos, photoStorage, onPhotoClick)
        }
    }
}

@Composable
private fun GeneralCard(
    details: InspectionWithDetails,
    photos: List<PhotoEntity>,
    photoStorage: PhotoStorage,
    onNotes: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPhotoClick: (PhotoEntity) -> Unit,
) {
    var notes by rememberSaveable(details.inspection.id) { mutableStateOf(details.inspection.generalNotes) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("General notes & photos", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = notes,
                onValueChange = {
                    notes = it.take(4_000)
                    onNotes(notes)
                },
                label = { Text("General notes (optional)") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("general-notes"),
            )
            TextButton(onClick = onTakePhoto, modifier = Modifier.testTag("general-photo")) {
                Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add general photo")
            }
            PhotoStrip(photos, photoStorage, onPhotoClick)
        }
    }
}

@Composable
private fun PhotoStrip(photos: List<PhotoEntity>, photoStorage: PhotoStorage, onPhotoClick: (PhotoEntity) -> Unit) {
    if (photos.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos, key = { it.id }) { photo ->
            PhotoThumbnail(file = photoStorage.file(photo.fileName), onClick = { onPhotoClick(photo) })
        }
    }
}

@Composable
private fun SubmitBar(summary: InspectionSummary, completing: Boolean, onComplete: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${summary.answered}/${summary.total} answered", style = MaterialTheme.typography.titleSmall)
                if (summary.unanswered > 0) {
                    Text("${summary.unanswered} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onComplete, enabled = !completing, modifier = Modifier.testTag("complete-inspection")) {
                if (completing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(painterResource(R.drawable.ic_pdf), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Submit & create PDF")
            }
        }
    }
}
