package com.safestitch.app.ui.checkpoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.DuplicateCheckpointCodeException
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.LocationSelection
import com.safestitch.app.ui.components.LocationSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CheckpointFormViewModel(
    private val repository: InspectionRepository,
    catalog: CatalogRepository,
    private val user: UserEntity,
    val scannedCode: String?,
    private val checkpointId: Long?,
) : ViewModel() {
    sealed interface Event {
        data object Saved : Event
        data class InspectionReady(val inspectionId: Long) : Event
    }

    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val paths: StateFlow<List<AreaPath>> = catalog.observeAreaPaths().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var code by mutableStateOf(scannedCode.orEmpty())
    var name by mutableStateOf("")
    var spot by mutableStateOf("")
    var type by mutableStateOf(CheckpointType.SEWING_LINE)
    var location by mutableStateOf(LocationSelection())
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    val isEditing = checkpointId != null
    val codeLocked = scannedCode != null || checkpointId != null

    init {
        if (checkpointId != null) {
            viewModelScope.launch {
                repository.getCheckpoint(checkpointId)?.let { cp ->
                    code = cp.code
                    name = cp.name
                    spot = cp.area
                    type = cp.type
                    location = LocationSelection.of(cp.areaId?.let { id -> catalog.observeAreaPaths().first().firstOrNull { it.areaId == id } })
                }
            }
        }
    }

    fun save() {
        if (saving) return
        error = null
        val normalized = QrPayload.parseCheckpointCode(code)
        when {
            normalized == null -> error = "Enter the code printed on (or planned for) its QR label."
            name.isBlank() -> error = "Give the equipment a name, for example \"Fire extinguisher FE-12\"."
            !location.isEmpty && location.areaId == null -> error = "Choose down to the area, or set the factory back to \"Not set\"."
        }
        if (error != null || normalized == null) return
        saving = true
        viewModelScope.launch {
            try {
                if (checkpointId != null) {
                    repository.updateCheckpoint(checkpointId, name, spot, type, location.areaId)
                    channel.send(Event.Saved)
                } else {
                    val newId = repository.createCheckpoint(normalized, name, spot, type, location.areaId)
                    if (scannedCode != null) {
                        channel.send(Event.InspectionReady(repository.startOrResumeInspection(newId, user.displayName, user.id)))
                    } else {
                        channel.send(Event.Saved)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DuplicateCheckpointCodeException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not save: ${e.message}"
            } finally {
                saving = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointFormScreen(
    container: AppContainer,
    user: UserEntity,
    scannedCode: String?,
    checkpointId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onInspectionReady: (Long) -> Unit,
) {
    val vm = viewModel { CheckpointFormViewModel(container.repository, container.catalog, user, scannedCode, checkpointId) }
    val paths by vm.paths.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                CheckpointFormViewModel.Event.Saved -> onSaved()
                is CheckpointFormViewModel.Event.InspectionReady -> onInspectionReady(it.inspectionId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isEditing) "Edit equipment" else "New equipment") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (vm.scannedCode != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "Code ${vm.scannedCode} is not registered yet. Describe it once; every later scan opens its checklist straight away.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            OutlinedTextField(
                value = vm.code,
                onValueChange = { vm.code = it.take(QrPayload.MAX_CODE_LENGTH) },
                label = { Text("Equipment code (on the QR label)") },
                readOnly = vm.codeLocked,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("cp-code"),
            )
            OutlinedTextField(
                value = vm.name,
                onValueChange = { vm.name = it.take(80) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Fire extinguisher FE-12") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("cp-name"),
            )
            Text("Location", style = MaterialTheme.typography.titleSmall)
            if (paths.isEmpty()) {
                Text(
                    "No locations yet. An admin can add factory › building › floor › area under Admin › Locations; " +
                        "you can assign this equipment later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LocationSelector(paths, vm.location, { vm.location = it }, noneLabel = "Not set", tagPrefix = "cp-loc")
            }
            OutlinedTextField(
                value = vm.spot,
                onValueChange = { vm.spot = it.take(80) },
                label = { Text("Exact spot (optional)") },
                placeholder = { Text("e.g. next to stair B") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("cp-area"),
            )
            Text("Type (decides the checklist)", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                CheckpointType.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = vm.type == option, onClick = { vm.type = option }, role = Role.RadioButton)
                            .padding(vertical = 8.dp)
                            .testTag("cp-type-${option.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.type == option, onClick = null)
                        Text(option.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("cp-error"))
            }
            Button(onClick = vm::save, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().testTag("cp-save")) {
                Text(
                    when {
                        vm.scannedCode != null -> "Save and start inspection"
                        vm.isEditing -> "Save changes"
                        else -> "Save equipment"
                    },
                )
            }
        }
    }
}
