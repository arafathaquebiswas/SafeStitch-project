package com.safestitch.app.ui.inspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.ui.components.LocationSelection
import com.safestitch.app.ui.components.LocationSelector
import com.safestitch.app.util.FileSharing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class FindEquipmentViewModel(
    private val repository: InspectionRepository,
    catalog: CatalogRepository,
    private val reportService: ReportService,
) : ViewModel() {
    sealed interface Event {
        data class InspectionReady(val inspectionId: Long) : Event
        data class Message(val text: String) : Event
    }

    val equipment: StateFlow<List<CheckpointEntity>?> =
        repository.observeCheckpoints().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val paths: StateFlow<List<AreaPath>> = catalog.observeAreaPaths().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    var query by mutableStateOf("")
    var selection by mutableStateOf(LocationSelection())
    var labels by mutableStateOf<Pair<File, Int>?>(null)
    var busy by mutableStateOf(false)
        private set

    fun start(checkpoint: CheckpointEntity, user: UserEntity) = launchBusy {
        channel.send(Event.InspectionReady(repository.startOrResumeInspection(checkpoint.id, user.displayName, user.id)))
    }

    fun printLabels() = launchBusy {
        val all = repository.getCheckpoints()
        if (all.isEmpty()) channel.send(Event.Message("Add equipment first.")) else labels = reportService.generateLabels(all) to all.size
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
                channel.send(Event.Message(e.message ?: "Something went wrong."))
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindEquipmentScreen(
    container: AppContainer,
    user: UserEntity,
    onScan: () -> Unit,
    onInspectionReady: (Long) -> Unit,
    onAddEquipment: () -> Unit,
    onEditEquipment: (Long) -> Unit,
) {
    val vm = viewModel { FindEquipmentViewModel(container.repository, container.catalog, container.reportService) }
    val equipment by vm.equipment.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                is FindEquipmentViewModel.Event.InspectionReady -> onInspectionReady(it.inspectionId)
                is FindEquipmentViewModel.Event.Message -> snackbar.showSnackbar(it.text)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("Inspect") },
                actions = {
                    if (user.isAdmin) {
                        IconButton(onClick = vm::printLabels, enabled = !vm.busy, modifier = Modifier.testTag("print-labels")) {
                            Icon(painterResource(R.drawable.ic_pdf), contentDescription = "Print QR labels")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (user.isAdmin) {
                FloatingActionButton(onClick = onAddEquipment, modifier = Modifier.testTag("add-equipment")) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Add equipment")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val pathById = paths.associateBy { it.areaId }
        val q = vm.query.trim().lowercase(Locale.ROOT)
        val filtered = equipment.orEmpty().filter { cp ->
            val path = cp.areaId?.let(pathById::get)
            vm.selection.matches(path) &&
                (q.isEmpty() || listOf(cp.name, cp.code, cp.area, path?.label.orEmpty()).any { it.lowercase(Locale.ROOT).contains(q) })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("equipment-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().testTag("scan-button")) {
                    Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null)
                    Text("  Scan the equipment's QR code")
                }
            }
            item {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it.take(60) },
                    label = { Text("Search name, code or location") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("equipment-search"),
                )
            }
            if (paths.isNotEmpty()) {
                item { LocationSelector(paths, vm.selection, { vm.selection = it }, noneLabel = "All", tagPrefix = "find") }
            }
            item {
                Text(
                    "${filtered.size} of ${equipment.orEmpty().size} equipment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("equipment-count"),
                )
            }
            if (equipment?.isEmpty() == true) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            if (user.isAdmin) {
                                "No equipment yet. Set up locations under Admin › Locations, then tap + to add equipment, or scan a new QR label."
                            } else {
                                "No equipment yet. Scan a QR label, or ask an admin to add equipment."
                            },
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            items(filtered, key = { it.id }) { cp ->
                val path = cp.areaId?.let(pathById::get)
                Card(Modifier.fillMaxWidth().testTag("equipment-${cp.code}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cp.name, style = MaterialTheme.typography.titleMedium)
                        Text("${cp.code}  ·  ${cp.type.label}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(path?.label ?: "Location not set", cp.area.ifBlank { null }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Button(onClick = { vm.start(cp, user) }, enabled = !vm.busy, modifier = Modifier.testTag("inspect-${cp.code}")) { Text("Inspect") }
                            if (user.isAdmin) TextButton(onClick = { onEditEquipment(cp.id) }) { Text("Edit") }
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
            text = { Text("$count label${if (count == 1) "" else "s"} on A4. Print, cut along the dashed lines and stick each label on its equipment.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.labels = null
                    FileSharing.sharePdf(context, file, "SafeStitch QR labels")
                }) { Text("Share / print") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.labels = null
                    if (!FileSharing.openPdf(context, file)) FileSharing.sharePdf(context, file, "SafeStitch QR labels")
                }) { Text("Open") }
            },
        )
    }
}
