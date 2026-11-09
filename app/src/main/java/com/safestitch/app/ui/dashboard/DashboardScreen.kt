package com.safestitch.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.DashboardAction
import com.safestitch.app.domain.DashboardCalculator
import com.safestitch.app.domain.DashboardInspection
import com.safestitch.app.domain.DashboardStats
import com.safestitch.app.sync.SyncManager
import com.safestitch.app.sync.SyncPhase
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(repository: InspectionRepository, catalog: CatalogRepository, sync: SyncManager) : ViewModel() {
    val stats: StateFlow<DashboardStats?> = combine(
        repository.observeInspections(),
        repository.observeActionRows(),
        catalog.observeAreaPaths(),
    ) { inspections, actions, paths ->
        val byArea = paths.associateBy { it.areaId }
        DashboardCalculator.compute(
            inspections = inspections.map { details ->
                val summary = details.summary()
                DashboardInspection(
                    completedAt = details.inspection.completedAt,
                    outcome = summary.outcome,
                    scorePercent = summary.scorePercent,
                    locationGroup = details.checkpoint.areaId?.let(byArea::get)?.buildingLabel.orEmpty(),
                )
            },
            actions = actions.map { DashboardAction(it.action.status, it.action.priority, it.action.dueAt, it.action.resolvedAt, it.action.verifiedAt) },
            now = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncConfig = sync.config
    val syncStatus = sync.status
    val syncPending: StateFlow<Int> = sync.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncPhase: StateFlow<SyncPhase> = sync.phase.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncPhase.OFF)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    container: AppContainer,
    user: UserEntity,
    onScan: () -> Unit,
    onFindEquipment: () -> Unit,
    onOpenActions: () -> Unit,
    onAdmin: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = viewModel { DashboardViewModel(container.repository, container.catalog, container.syncManager) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val syncConfig by vm.syncConfig.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncPending by vm.syncPending.collectAsStateWithLifecycle()
    val syncPhase by vm.syncPhase.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SafeStitch", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${user.displayName} · ${user.role.label}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("signed-in-as"),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("account-menu")) {
                            Icon(painterResource(R.drawable.ic_person), contentDescription = "Account")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (user.isAdmin) {
                                DropdownMenuItem(
                                    text = { Text("Admin tools") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_admin), null) },
                                    onClick = { menuOpen = false; onAdmin() },
                                    modifier = Modifier.testTag("menu-admin"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), null) },
                                onClick = { menuOpen = false; onSettings() },
                                modifier = Modifier.testTag("menu-settings"),
                            )
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_logout), null) },
                                onClick = { menuOpen = false; container.auth.signOut() },
                                modifier = Modifier.testTag("menu-signout"),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val s = stats
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("dashboard"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f).testTag("dash-scan")) {
                        Icon(painterResource(R.drawable.ic_qr_scan), null)
                        Text("  Scan QR")
                    }
                    OutlinedButton(onClick = onFindEquipment, modifier = Modifier.weight(1f).testTag("dash-find")) {
                        Icon(painterResource(R.drawable.ic_search), null)
                        Text("  Find equipment")
                    }
                }
            }
            if (s != null) {
                item { SectionTitle("Inspections · last ${s.windowDays} days") }
                item {
                    KpiRow(
                        Kpi("Completed", s.completed.toString(), "kpi-completed"),
                        Kpi("Pass rate", s.passRatePercent?.let { "$it%" } ?: "—", "kpi-pass-rate"),
                    )
                }
                item {
                    KpiRow(
                        Kpi("Average score", s.averageScorePercent?.let { "$it%" } ?: "—", "kpi-avg-score"),
                        Kpi("Drafts in progress", s.drafts.toString(), "kpi-drafts"),
                    )
                }
                item { SectionTitle("Corrective actions") }
                item {
                    KpiRow(
                        Kpi("Open", s.openActions.toString(), "kpi-open-actions", onClick = onOpenActions),
                        Kpi("Overdue", s.overdueActions.toString(), "kpi-overdue", if (s.overdueActions > 0) LocalStatusColors.current.fail else null, onOpenActions),
                    )
                }
                item {
                    KpiRow(
                        Kpi("Awaiting verification", s.awaitingVerification.toString(), "kpi-awaiting", onClick = onOpenActions),
                        Kpi("Critical / high not verified", s.openCriticalOrHigh.toString(), "kpi-critical", onClick = onOpenActions),
                    )
                }
                item {
                    KpiRow(
                        Kpi("Verified · ${s.windowDays} days", s.verifiedInWindow.toString(), "kpi-verified"),
                        Kpi("Fixed on time", s.onTimeClosurePercent?.let { "$it%" } ?: "—", "kpi-on-time"),
                    )
                }
                if (s.byLocation.isNotEmpty()) {
                    item { SectionTitle("Pass rate by building") }
                    items(s.byLocation, key = { it.label }) { stat ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(stat.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text("${stat.passRatePercent}%  (${stat.passed}/${stat.completed})", style = MaterialTheme.typography.bodyMedium)
                                }
                                LinearProgressIndicator(progress = { stat.passRatePercent / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Works offline", style = MaterialTheme.typography.titleSmall)
                        if (!syncConfig.enabled) {
                            Text("Everything is stored on this phone. An admin can turn on optional sync under Admin › Sync.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Sync: ${syncPhase.label} · $syncPending waiting to upload" +
                                    (syncStatus.lastSuccessAt?.let { "\nLast synced ${formatDateTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("dash-sync"),
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Kpi(val label: String, val value: String, val tag: String, val color: Color? = null, val onClick: (() -> Unit)? = null)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun KpiRow(first: Kpi, second: Kpi) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard(first, Modifier.weight(1f))
        KpiCard(second, Modifier.weight(1f))
    }
}

@Composable
private fun KpiCard(kpi: Kpi, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp)) {
            Text(
                kpi.value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = kpi.color ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(kpi.tag),
            )
            Text(kpi.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (kpi.onClick != null) Card(onClick = kpi.onClick, modifier = modifier) { content() } else Card(modifier) { content() }
}
