package com.safestitch.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.domain.HistoryEntry
import com.safestitch.app.domain.HistoryFilter
import com.safestitch.app.domain.Outcome
import com.safestitch.app.domain.PeriodFilter
import com.safestitch.app.domain.ResultFilter
import com.safestitch.app.domain.StatusFilter
import com.safestitch.app.ui.components.LocationSelection
import com.safestitch.app.ui.components.LocationSelector
import com.safestitch.app.ui.components.SelectField
import com.safestitch.app.ui.components.SelectOption
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.components.outcomeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(repository: InspectionRepository, catalog: CatalogRepository) : ViewModel() {
    val filter = MutableStateFlow(HistoryFilter())
    val paths: StateFlow<List<AreaPath>> = catalog.observeAreaPaths().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val entries: StateFlow<List<HistoryEntry>?> = combine(repository.observeInspections(), catalog.observeAreaPaths()) { list, paths ->
        val byArea = paths.associateBy { it.areaId }
        list.map { d ->
            val path = d.checkpoint.areaId?.let(byArea::get)
            HistoryEntry(
                inspectionId = d.inspection.id,
                equipmentName = d.checkpoint.name,
                equipmentCode = d.checkpoint.code,
                type = d.checkpoint.type,
                factoryId = path?.factoryId,
                buildingId = path?.buildingId,
                floorId = path?.floorId,
                areaId = path?.areaId,
                locationLabel = d.inspection.siteName.ifBlank { path?.label.orEmpty() },
                inspectorName = d.inspection.inspectorName,
                startedAt = d.inspection.startedAt,
                completedAt = d.inspection.completedAt,
                summary = d.summary(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val all: StateFlow<List<HistoryEntry>?> = entries
    val shown: StateFlow<List<HistoryEntry>?> = combine(entries, filter) { list, f -> list?.let { f.apply(it, System.currentTimeMillis()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (HistoryFilter) -> HistoryFilter) {
        filter.value = transform(filter.value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(container: AppContainer, onOpen: (id: Long, completed: Boolean) -> Unit) {
    val vm = viewModel { HistoryViewModel(container.repository, container.catalog) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    var moreFilters by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("Inspection history") },
                actions = {
                    if (!filter.isDefault) {
                        TextButton(onClick = { vm.update { HistoryFilter() } }, modifier = Modifier.testTag("history-clear")) { Text("Clear filters") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("history-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { q -> vm.update { it.copy(query = q.take(60)) } },
                    label = { Text("Search equipment, location or inspector") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("history-search"),
                )
            }
            item { ChipRow(StatusFilter.entries, filter.status, { it.label }, "history-status") { s -> vm.update { it.copy(status = s) } } }
            item { ChipRow(ResultFilter.entries, filter.result, { it.label }, "history-result") { r -> vm.update { it.copy(result = r) } } }
            item { ChipRow(PeriodFilter.entries, filter.period, { it.label }, "history-period") { p -> vm.update { it.copy(period = p) } } }
            item {
                TextButton(onClick = { moreFilters = !moreFilters }, modifier = Modifier.testTag("history-more")) {
                    Text(if (moreFilters) "Hide location, inspector and type filters" else "More filters: location, inspector, type")
                }
            }
            if (moreFilters) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (paths.isNotEmpty()) {
                            LocationSelector(
                                paths = paths,
                                selection = LocationSelection(filter.factoryId, filter.buildingId, filter.floorId, filter.areaId),
                                onChange = { sel -> vm.update { it.copy(factoryId = sel.factoryId, buildingId = sel.buildingId, floorId = sel.floorId, areaId = sel.areaId) } },
                                noneLabel = "All",
                                tagPrefix = "hist",
                            )
                        }
                        val inspectors = all.orEmpty().map { it.inspectorName }.filter { it.isNotBlank() }.distinct().sorted()
                        SelectField(
                            label = "Inspector",
                            selectedLabel = filter.inspectorName ?: "All",
                            options = listOf(SelectOption<String?>(null, "All")) + inspectors.map { SelectOption(it, it) },
                            onSelect = { name -> vm.update { it.copy(inspectorName = name) } },
                            testTag = "hist-inspector",
                        )
                        SelectField(
                            label = "Equipment type",
                            selectedLabel = filter.type?.label ?: "All",
                            options = listOf(SelectOption<CheckpointType?>(null, "All")) + CheckpointType.entries.map { SelectOption(it, it.label) },
                            onSelect = { t -> vm.update { it.copy(type = t) } },
                            testTag = "hist-type",
                        )
                    }
                }
            }
            item {
                val count = shown.orEmpty().size
                Text(
                    "$count of ${all.orEmpty().size} inspection${if (all.orEmpty().size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("history-count"),
                )
            }
            items(shown.orEmpty(), key = { it.inspectionId }) { entry ->
                HistoryCard(entry, onClick = { onOpen(entry.inspectionId, entry.completedAt != null) })
            }
        }
    }
}

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: (T) -> String, tagPrefix: String, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values) { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
                modifier = Modifier.testTag("$tagPrefix-$value"),
            )
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("inspection-${entry.inspectionId}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.equipmentName, style = MaterialTheme.typography.titleMedium)
                Text("${entry.equipmentCode}  ·  ${entry.inspectorName}", style = MaterialTheme.typography.bodySmall)
                if (entry.locationLabel.isNotBlank()) {
                    Text(entry.locationLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    entry.completedAt?.let { "Submitted ${formatDateTime(it)}" } ?: "Started ${formatDateTime(entry.startedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            val s = entry.summary
            if (entry.completedAt == null) {
                StatusChip("Draft ${s.answered}/${s.total}", outcomeColor(Outcome.IN_PROGRESS))
            } else {
                StatusChip("${s.outcome.label} ${s.scorePercent}%", outcomeColor(s.outcome))
            }
        }
    }
}
