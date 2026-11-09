package com.safestitch.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.domain.Outcome
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.components.outcomeColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class InspectionListItem(
    val id: Long,
    val checkpointName: String,
    val checkpointCode: String,
    val area: String,
    val startedAt: Long,
    val completedAt: Long?,
    val summary: InspectionSummary,
)

class HomeViewModel(repository: InspectionRepository) : ViewModel() {
    /** null while the database is loading. */
    val inspections: StateFlow<List<InspectionListItem>?> = repository.observeInspections()
        .map { list ->
            list.map {
                InspectionListItem(
                    id = it.inspection.id,
                    checkpointName = it.checkpoint.name,
                    checkpointCode = it.checkpoint.code,
                    area = it.checkpoint.area,
                    startedAt = it.inspection.startedAt,
                    completedAt = it.inspection.completedAt,
                    summary = it.summary(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onScan: () -> Unit,
    onOpenInspection: (id: Long, completed: Boolean) -> Unit,
    onCheckpoints: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = viewModel { HomeViewModel(container.repository) }
    val items by vm.inspections.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SafeStitch", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onCheckpoints, modifier = Modifier.testTag("nav-checkpoints")) {
                        Icon(painterResource(R.drawable.ic_location), contentDescription = "Checkpoints")
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("nav-settings")) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null) },
                text = { Text("Scan QR") },
                modifier = Modifier.testTag("scan-fab"),
            )
        },
    ) { padding ->
        val list = items
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home-list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OfflineBanner() }
            when {
                list == null -> Unit
                list.isEmpty() -> item { EmptyHome(onScan = onScan, onCheckpoints = onCheckpoints) }
                else -> {
                    item {
                        Text("Inspections", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(list, key = { it.id }) { item ->
                        InspectionCard(item, onClick = { onOpenInspection(item.id, item.completedAt != null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            "Works offline. Inspections, photos and PDF reports are stored only on this phone until you share them.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EmptyHome(onScan: () -> Unit, onCheckpoints: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No inspections yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scan the QR label on a sewing line, fire point or electrical panel to start its checklist. " +
                    "No label yet? Add the checkpoint by hand and print labels from the Checkpoints screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onScan) { Text("Scan QR") }
                OutlinedButton(onClick = onCheckpoints, modifier = Modifier.testTag("start-without-qr")) { Text("Start without QR") }
            }
        }
    }
}

@Composable
private fun InspectionCard(item: InspectionListItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("inspection-${item.id}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.checkpointName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(item.checkpointCode, item.area).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (item.completedAt != null) "Completed ${formatDateTime(item.completedAt)}" else "Started ${formatDateTime(item.startedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            val summary = item.summary
            if (item.completedAt == null) {
                StatusChip("Draft ${summary.answered}/${summary.total}", outcomeColor(Outcome.IN_PROGRESS))
            } else {
                StatusChip("${summary.outcome.label} ${summary.scorePercent}%", outcomeColor(summary.outcome))
            }
        }
    }
}
