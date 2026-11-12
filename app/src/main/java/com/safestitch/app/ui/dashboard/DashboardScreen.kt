package com.safestitch.app.ui.dashboard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.safestitch.app.audio.AudioTranscriptionService
import com.safestitch.app.data.CatalogRepository
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.DashboardAction
import com.safestitch.app.domain.DashboardCalculator
import com.safestitch.app.domain.DashboardInspection
import com.safestitch.app.domain.DashboardStats
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.qr.QrCodeAnalyzer
import com.safestitch.app.qr.QrCodec
import com.safestitch.app.sync.SyncManager
import com.safestitch.app.sync.SyncPhase
import com.safestitch.app.ui.camera.CameraPreview
import com.safestitch.app.ui.components.CameraPermissionGate
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

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

    val checkpoints: StateFlow<List<CheckpointEntity>> = repository
        .observeCheckpoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summaryMetrics: StateFlow<SummaryDashboardMetrics?> = combine(
        repository.observeInspections(),
        capturedInspections,
        checkpoints,
        catalog.observeAreaPaths(),
    ) { inspections, captured, allCheckpoints, paths ->
        SummaryDashboardCalculator.compute(
            inspections = inspections,
            capturedInspections = captured,
            checkpoints = allCheckpoints,
            areaPaths = paths,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /**
     * Identifies equipment from a ZXing-decoded QR or barcode string and invokes [onIdentified]
     * with the normalized equipment code so the dashboard navigates directly to its inspection history.
     */
    fun identifyEquipmentFromScan(
        rawCode: String,
        onIdentified: (equipmentCode: String, equipmentName: String?) -> Unit,
        onInvalid: (String) -> Unit,
    ) {
        val normalized = QrPayload.parseCheckpointCode(rawCode)
        if (normalized == null) {
            onInvalid("Unrecognized QR or barcode payload.")
            return
        }
        viewModelScope.launch {
            val matched = repository.findCheckpointByCode(normalized)
            onIdentified(matched?.code ?: normalized, matched?.name)
        }
    }

    /** Decodes a QR code or 1D/2D barcode from an image selected on the dashboard. */
    fun scanEquipmentFromImage(
        context: Context,
        uri: Uri,
        onIdentified: (equipmentCode: String, equipmentName: String?) -> Unit,
        onInvalid: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    QrCodec.decodeArgb(pixels, bitmap.width, bitmap.height).also { bitmap.recycle() }
                }.getOrNull()
            }
            if (decoded == null) {
                onInvalid("No QR code or barcode was found in that picture.")
            } else {
                identifyEquipmentFromScan(decoded, onIdentified, onInvalid)
            }
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
    onOpenChecklistInspection: (inspectionId: Long, completed: Boolean) -> Unit = { _, _ -> },
    onOpenCapturedInspectionDetail: (capturedId: Long) -> Unit = {},
    onNavigateToEquipmentHistory: (equipmentCode: String) -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val vm = viewModel { DashboardViewModel(container.repository, container.catalog, container.syncManager) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    val capturedInspections by vm.capturedInspections.collectAsStateWithLifecycle()
    val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()
    val summaryMetrics by vm.summaryMetrics.collectAsStateWithLifecycle()
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
    var scanEquipmentMode by rememberSaveable { mutableStateOf(true) }
    var manualBarcodeInput by rememberSaveable { mutableStateOf("") }
    var selectedCaptureTag by rememberSaveable { mutableStateOf(Inspection.TAG_NEEDS_REVIEW) }
    var activeFilterTag by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val transcribeService = remember { AudioTranscriptionService() }
    var isRecordingVoiceNote by remember { mutableStateOf(false) }
    var isTranscribingVoiceNote by remember { mutableStateOf(false) }
    var voiceTranscriptText by rememberSaveable { mutableStateOf("") }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(85)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    lateinit var zxingAnalyzer: QrCodeAnalyzer
    zxingAnalyzer = remember {
        QrCodeAnalyzer { rawText ->
            mainExecutor.execute {
                vm.identifyEquipmentFromScan(
                    rawCode = rawText,
                    onIdentified = { code, _ ->
                        onNavigateToEquipmentHistory(code)
                    },
                    onInvalid = { msg ->
                        scope.launch {
                            snackbar.showSnackbar(msg)
                            zxingAnalyzer.resume()
                        }
                    },
                )
            }
        }
    }
    val barcodeAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .build()
            .also { it.setAnalyzer(analysisExecutor, zxingAnalyzer) }
    }
    val cameraUseCases = remember { listOf(imageCapture, barcodeAnalysis) }
    DisposableEffect(Unit) {
        onDispose {
            barcodeAnalysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }

    val pickBarcodeImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            vm.scanEquipmentFromImage(
                context = context,
                uri = uri,
                onIdentified = { code, _ -> onNavigateToEquipmentHistory(code) },
                onInvalid = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            )
        }
    }

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
                                text = { Text("Safety Assistant Chat") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_assignment), null) },
                                onClick = { menuOpen = false; onOpenChat() },
                                modifier = Modifier.testTag("menu-chat"),
                            )
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
                                    "Live Camera & ZXing Equipment Scanner",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Auto-identifies equipment via QR or barcode and opens its inspection history",
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
                                FilterChip(
                                    selected = scanEquipmentMode,
                                    onClick = {
                                        scanEquipmentMode = !scanEquipmentMode
                                        if (scanEquipmentMode) zxingAnalyzer.resume()
                                    },
                                    label = { Text("ZXing QR / Barcode Active", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("viewfinder-scan-mode-chip"),
                                )
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
                                explanation = "Allow camera access to scan equipment QR/barcodes with ZXing or capture inspection photos.",
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
                                        color = Color.Black.copy(alpha = 0.58f),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 12.dp, bottom = 12.dp, end = 80.dp),
                                    ) {
                                        Text(
                                            text = cameraError
                                                ?: "ZXing live: Point at QR or barcode to open equipment history, or tap shutter",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (scanEquipmentMode) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dash-scan-equipment-panel"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Scan Equipment (QR / Barcode → History)",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = "Supports QR_CODE, CODE_128, CODE_39, EAN_13 & DATA_MATRIX via ZXing.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                pickBarcodeImage.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            },
                                            modifier = Modifier.testTag("dash-scan-equipment-image"),
                                        ) {
                                            Text("From image", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    if (checkpoints.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "Quick scan tag:",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            checkpoints.forEach { cp ->
                                                AssistChip(
                                                    onClick = {
                                                        vm.identifyEquipmentFromScan(
                                                            rawCode = QrPayload.forCheckpoint(cp.code),
                                                            onIdentified = { code, _ -> onNavigateToEquipmentHistory(code) },
                                                            onInvalid = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                                                        )
                                                    },
                                                    label = { Text("${cp.code} · ${cp.name}", style = MaterialTheme.typography.labelSmall) },
                                                    modifier = Modifier.testTag("dash-scan-chip-${cp.code}"),
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = manualBarcodeInput,
                                            onValueChange = { manualBarcodeInput = it.take(QrPayload.MAX_CODE_LENGTH) },
                                            label = { Text("Enter or scan QR / barcode (e.g. FE-101)") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("dash-scan-code-input"),
                                        )
                                        Button(
                                            onClick = {
                                                val codeToScan = manualBarcodeInput
                                                manualBarcodeInput = ""
                                                vm.identifyEquipmentFromScan(
                                                    rawCode = codeToScan,
                                                    onIdentified = { code, _ -> onNavigateToEquipmentHistory(code) },
                                                    onInvalid = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                                                )
                                            },
                                            enabled = manualBarcodeInput.isNotBlank(),
                                            modifier = Modifier.testTag("dash-scan-code-submit"),
                                        ) {
                                            Text("History")
                                        }
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val firstCode = checkpoints.firstOrNull()?.code
                            if (manualBarcodeInput.isNotBlank()) {
                                val raw = manualBarcodeInput
                                manualBarcodeInput = ""
                                vm.identifyEquipmentFromScan(
                                    rawCode = raw,
                                    onIdentified = { code, _ -> onNavigateToEquipmentHistory(code) },
                                    onInvalid = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                                )
                            } else if (firstCode != null) {
                                onNavigateToEquipmentHistory(firstCode)
                            } else {
                                showViewfinder = true
                                scanEquipmentMode = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dash-scan-equipment"),
                    ) {
                        Icon(painterResource(R.drawable.ic_qr_scan), null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Equipment (QR / Barcode → Inspection History)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f).testTag("dash-scan")) {
                            Icon(painterResource(R.drawable.ic_qr_scan), null)
                            Text("  Scan for Checklist")
                        }
                        OutlinedButton(onClick = onFindEquipment, modifier = Modifier.weight(1f).testTag("dash-find")) {
                            Icon(painterResource(R.drawable.ic_search), null)
                            Text("  Find equipment")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenChat,
                        modifier = Modifier.fillMaxWidth().testTag("dash-open-chat"),
                    ) {
                        Icon(painterResource(R.drawable.ic_assignment), null)
                        Spacer(Modifier.width(8.dp))
                        Text("Safety Assistant Chat (Multi-Turn Advisor)")
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dash-voice-transcribe-card"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Voice Note Transcription",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Record microphone audio & transcribe via ${AudioTranscriptionService.MODEL_TRANSCRIBE}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    enabled = !isTranscribingVoiceNote,
                                    onClick = {
                                        if (!isRecordingVoiceNote) {
                                            transcribeService.startMicrophoneRecording()
                                            isRecordingVoiceNote = true
                                        } else {
                                            isRecordingVoiceNote = false
                                            isTranscribingVoiceNote = true
                                            scope.launch {
                                                val result = transcribeService.stopAndTranscribe(
                                                    contextHint = checkpoints.firstOrNull()?.code.orEmpty(),
                                                )
                                                isTranscribingVoiceNote = false
                                                voiceTranscriptText = result.text
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("dash-mic-record-btn"),
                                ) {
                                    Text(
                                        when {
                                            isRecordingVoiceNote -> "Stop & Transcribe"
                                            isTranscribingVoiceNote -> "Transcribing…"
                                            else -> "Record Mic"
                                        },
                                    )
                                }
                            }
                            if (voiceTranscriptText.isNotBlank()) {
                                OutlinedTextField(
                                    value = voiceTranscriptText,
                                    onValueChange = { voiceTranscriptText = it },
                                    label = { Text("Transcribed Audio (${AudioTranscriptionService.MODEL_TRANSCRIBE})") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dash-transcribe-result"),
                                )
                            }
                        }
                    }
                }
            }
            summaryMetrics?.let { summary ->
                item {
                    SummaryDashboardCard(
                        metrics = summary,
                        onOpenChecklistInspection = onOpenChecklistInspection,
                        onOpenCapturedInspection = onOpenCapturedInspectionDetail,
                    )
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
