package com.safestitch.app.ui.checkpoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.SettingsRepository
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.util.FileSharing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class CheckpointsViewModel(
    private val repository: InspectionRepository,
    private val settings: SettingsRepository,
    private val reportService: ReportService,
) : ViewModel() {
    sealed interface Event {
        data class InspectionReady(val inspectionId: Long) : Event
        data class Message(val text: String) : Event
    }

    val checkpoints: StateFlow<List<CheckpointEntity>?> =
        repository.observeCheckpoints().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    var labels by mutableStateOf<Pair<File, Int>?>(null)
    var busy by mutableStateOf(false)
        private set

    fun startInspection(checkpoint: CheckpointEntity) = launchBusy {
        val defaults = settings.settings.value
        channel.send(Event.InspectionReady(repository.startOrResumeInspection(checkpoint.id, defaults.inspectorName, defaults.siteName)))
    }

    fun printLabels() = launchBusy {
        val all = repository.getCheckpoints()
        if (all.isEmpty()) {
            channel.send(Event.Message("Add a checkpoint first."))
        } else {
            labels = reportService.generateLabels(all) to all.size
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.send(Event.Message("Something went wrong: ${e.message}"))
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onInspectionReady: (Long) -> Unit,
    onAddCheckpoint: () -> Unit,
    onEditCheckpoint: (Long) -> Unit,
) {
    val vm = viewModel { CheckpointsViewModel(container.repository, container.settings, container.reportService) }
    val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                is CheckpointsViewModel.Event.InspectionReady -> onInspectionReady(it.inspectionId)
                is CheckpointsViewModel.Event.Message -> snackbar.showSnackbar(it.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkpoints") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = vm::printLabels, enabled = !vm.busy, modifier = Modifier.testTag("print-labels")) {
                        Icon(painterResource(R.drawable.ic_pdf), contentDescription = "Print QR labels")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCheckpoint, modifier = Modifier.testTag("add-checkpoint")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add checkpoint")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = checkpoints
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list != null && list.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No checkpoints yet. Tap + to add one, or scan a QR label from the home screen. " +
                                "The PDF button at the top prints QR labels for every checkpoint.",
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            items(list.orEmpty(), key = { it.id }) { checkpoint ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(checkpoint.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(checkpoint.code, checkpoint.type.label, checkpoint.area).filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(
                                onClick = { vm.startInspection(checkpoint) },
                                enabled = !vm.busy,
                                modifier = Modifier.testTag("inspect-${checkpoint.code}"),
                            ) { Text("Inspect") }
                            TextButton(onClick = { onEditCheckpoint(checkpoint.id) }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }

    vm.labels?.let { (file, count) ->
        AlertDialog(
            onDismissRequest = { vm.labels = null },
            title = { Text("QR labels ready") },
            text = { Text("$count label${if (count == 1) "" else "s"} on A4. Print the sheet, cut along the dashed lines and stick each label on its checkpoint.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.labels = null
                    FileSharing.sharePdf(context, file, "SafeStitch QR labels")
                }) { Text("Share / print") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.labels = null
                    if (!FileSharing.openPdf(context, file)) {
                        FileSharing.sharePdf(context, file, "SafeStitch QR labels")
                    }
                }) { Text("Open") }
            },
        )
    }
}
