package com.safestitch.app.ui.dashboard

import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.DashboardAction
import com.safestitch.app.domain.DashboardCalculator
import com.safestitch.app.domain.DashboardInspection
import com.safestitch.app.domain.DashboardStats
import com.safestitch.app.sync.SyncManager
import com.safestitch.app.sync.SyncPhase
import com.safestitch.app.ui.camera.CameraPreview
import com.safestitch.app.ui.components.CameraPermissionGate
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class DashboardViewModel(
    private val repository: InspectionRepository,
    catalog: CatalogRepository,
    sync: SyncManager,
) : ViewModel() {
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

    val capturedInspections: StateFlow<List<Inspection>> = repository
        .observeCapturedInspections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncConfig = sync.config
    val syncStatus = sync.status
    val syncPending: StateFlow<Int> = sync.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncPhase: StateFlow<SyncPhase> = sync.phase.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncPhase.OFF)

    fun saveCapture(imagePath: String, statusTag: String) {
        viewModelScope.launch {
            repository.recordCapturedInspection(
                imagePath = imagePath,
                statusTags = listOf(statusTag),
            )
        }
    }

    fun toggleTag(inspection: Inspection, tag: String) {
        viewModelScope.launch {
            repository.toggleCapturedInspectionTag(inspection, tag)
        }
    }
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
    onOpenCapturedInspections: () -> Unit = {},
) {
    val vm = viewModel { DashboardViewModel(container.repository, container.catalog, container.syncManager) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val capturedInspections by vm.capturedInspections.collectAsStateWithLifecycle()
    val syncConfig by vm.syncConfig.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncPending by vm.syncPending.collectAsStateWithLifecycle()
    val syncPhase by vm.syncPhase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showViewfinder by rememberSaveable { mutableStateOf(true) }
    var selectedCaptureTag by rememberSaveable { mutableStateOf(Inspection.TAG_NEEDS_REVIEW) }
    var activeFilterTag by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(85)
            .build()
    }
    val cameraUseCases = remember { listOf(imageCapture) }
    val filteredCaptures = remember(capturedInspections, activeFilterTag) {
        val filter = activeFilterTag
        if (filter == null) capturedInspections else capturedInspections.filter { it.hasTag(filter) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
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
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("main-camera-card"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Live Inspection Viewfinder",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Frame safety equipment, gauges, or QR tags in real time",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val cam = camera
                            if (showViewfinder && cam != null && cam.cameraInfo.hasFlashUnit()) {
                                IconButton(
                                    onClick = {
                                        torchOn = !torchOn
                                        cam.cameraControl.enableTorch(torchOn)
                                    },
                                    modifier = Modifier.testTag("main-camera-torch"),
                                ) {
                                    Icon(
                                        painterResource(if (torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash_on),
                                        contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { showViewfinder = !showViewfinder },
                                modifier = Modifier.testTag("toggle-viewfinder"),
                            ) {
                                Text(if (showViewfinder) "Hide" else "Show")
                            }
                        }

                        if (showViewfinder) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Capture tag:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Inspection.DEFAULT_STATUS_TAGS.forEach { tag ->
                                    FilterChip(
                                        selected = selectedCaptureTag == tag,
                                        onClick = { selectedCaptureTag = tag },
                                        label = { Text(tag.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("capture-tag-$tag"),
                                    )
                                }
                            }

                            CameraPermissionGate(
                                explanation = "Allow camera access to show the real-time viewfinder and frame inspection items on the main screen.",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 10f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black),
                                ) {
                                    CameraPreview(
                                        useCases = cameraUseCases,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("main-camera-preview"),
                                        showFramingOverlay = true,
                                        isCapturing = capturing,
                                        onCaptureClick = {
                                            if (capturing) return@CameraPreview
                                            capturing = true
                                            val file = container.photoStorage.newPhotoFile()
                                            val tagOnCapture = selectedCaptureTag
                                            view.display?.let { imageCapture.targetRotation = it.rotation }
                                            imageCapture.takePicture(
                                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                                ContextCompat.getMainExecutor(context),
                                                object : ImageCapture.OnImageSavedCallback {
                                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                        capturing = false
                                                        vm.saveCapture(file.absolutePath, tagOnCapture)
                                                        scope.launch {
                                                            snackbar.showSnackbar("Inspection photo saved with tag $tagOnCapture")
                                                        }
                                                    }

                                                    override fun onError(exception: ImageCaptureException) {
                                                        capturing = false
                                                        file.delete()
                                                        scope.launch {
                                                            snackbar.showSnackbar("Could not capture photo: ${exception.message}")
                                                        }
                                                    }
                                                },
                                            )
                                        },
                                        onCameraReady = {
                                            camera = it
                                            cameraError = null
                                        },
                                        onError = {
                                            cameraError = it.message ?: "Camera unavailable"
                                        },
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 12.dp, bottom = 12.dp, end = 80.dp),
                                    ) {
                                        Text(
                                            text = cameraError ?: "Align inspection item inside framing brackets",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (capturedInspections.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "Captured frames (${filteredCaptures.size}/${capturedInspections.size}):",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilterChip(
                                    selected = activeFilterTag == null,
                                    onClick = { activeFilterTag = null },
                                    label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("filter-tag-ALL"),
                                )
                                Inspection.DEFAULT_STATUS_TAGS.forEach { tag ->
                                    FilterChip(
                                        selected = activeFilterTag == tag,
                                        onClick = { activeFilterTag = if (activeFilterTag == tag) null else tag },
                                        label = { Text(tag.replace('_', ' '), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("filter-tag-$tag"),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                filteredCaptures.forEach { captured ->
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        horizontalAlignment = Alignment.Start,
                                    ) {
                                        PhotoThumbnail(
                                            file = File(captured.imagePath),
                                            onClick = { vm.toggleTag(captured, selectedCaptureTag) },
                                        )
                                        Text(
                                            text = captured.normalizedTags.joinToString(" · ").ifBlank { Inspection.TAG_NEEDS_REVIEW },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = formatDateTime(captured.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenCapturedInspections,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open-captured-inspections"),
                        ) {
                            Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null)
                            Text("  View all captured inspections (${capturedInspections.size})")
                        }
                    }
                }
            }
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
                item {
                    KpiRow(
                        Kpi("Inspections (all time)", s.totalInspections.toString(), "kpi-total-inspections"),
                        Kpi("Failed · ${s.windowDays} days", s.failed.toString(), "kpi-failed"),
                    )
                }
                item { SectionTitle("Corrective actions") }
                item {
                    KpiRow(
                        Kpi("Open", s.openActions.toString(), "kpi-open-actions", onClick = onOpenActions),
                        Kpi("In progress", s.inProgressActions.toString(), "kpi-in-progress", onClick = onOpenActions),
                    )
                }
                item {
                    KpiRow(
                        Kpi("Overdue", s.overdueActions.toString(), "kpi-overdue", if (s.overdueActions > 0) LocalStatusColors.current.fail else null, onOpenActions),
                        Kpi("Awaiting verification", s.awaitingVerification.toString(), "kpi-awaiting", onClick = onOpenActions),
                    )
                }
                item {
                    KpiRow(
                        Kpi("Critical / high not verified", s.openCriticalOrHigh.toString(), "kpi-critical", onClick = onOpenActions),
                        Kpi("All corrective actions", s.totalActions.toString(), "kpi-total-actions", onClick = onOpenActions),
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
