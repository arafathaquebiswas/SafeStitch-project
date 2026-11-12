package com.safestitch.app.ui.history

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.pdf.CapturedEquipmentDetails
import com.safestitch.app.pdf.PdfInfo
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.util.FileSharing
import com.safestitch.app.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DecodedFullImage(
    val bitmap: ImageBitmap?,
    val widthPx: Int,
    val heightPx: Int,
    val sizeBytes: Long,
    val existsOnDisk: Boolean,
)

class InspectionDetailsViewModel(
    private val inspectionId: Long,
    private val repository: InspectionRepository,
    private val reportService: ReportService? = null,
    private val activeInspectorName: String? = null,
) : ViewModel() {
    val inspection: StateFlow<Inspection?> = repository
        .observeCapturedInspection(inspectionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checkpoints: StateFlow<List<CheckpointEntity>> = repository
        .observeCheckpoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedCheckpointId: MutableStateFlow<Long?> = MutableStateFlow(
        reportService?.getSelectedCheckpointId(inspectionId),
    )

    val equipmentDetails: StateFlow<CapturedEquipmentDetails> = combine(
        inspection,
        checkpoints,
        repository.observeAreaPaths(),
        repository.observeInspections(),
        selectedCheckpointId,
    ) { current, allCheckpoints, areaPaths, allInspections, overrideId ->
        if (current == null) {
            CapturedEquipmentDetails(
                equipmentName = "Factory Floor Safety Checkpoint",
                equipmentCode = "SS-CAP-$inspectionId",
                equipmentCategory = "General safety",
                locationPath = "Main Factory › Production Floor",
                inspectorName = activeInspectorName ?: "Field Safety Inspector",
            )
        } else {
            InspectionRepository.resolveEquipmentFromCatalog(
                inspection = current,
                allCheckpoints = allCheckpoints,
                areaPaths = areaPaths,
                allInspections = allInspections,
                overrideCheckpointId = overrideId,
                activeInspectorName = activeInspectorName,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CapturedEquipmentDetails(
            equipmentName = "Factory Floor Safety Checkpoint",
            equipmentCode = "SS-CAP-$inspectionId",
            equipmentCategory = "General safety",
            locationPath = "Main Factory › Production Floor",
            inspectorName = activeInspectorName ?: "Field Safety Inspector",
        ),
    )

    val customTagInput: MutableStateFlow<String> = MutableStateFlow("")
    val exportedPdfFile: MutableStateFlow<File?> = MutableStateFlow(null)
    val exportedPdfInfo: MutableStateFlow<PdfInfo?> = MutableStateFlow(null)
    val pdfPreviewBitmap: MutableStateFlow<ImageBitmap?> = MutableStateFlow(null)
    val exportingPdf: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val statusMessage: MutableStateFlow<String?> = MutableStateFlow(null)

    fun selectEquipment(checkpointId: Long?) {
        selectedCheckpointId.value = checkpointId
        reportService?.setSelectedCheckpointId(inspectionId, checkpointId)
    }

    fun updateCustomTagInput(value: String) {
        customTagInput.value = value.take(32)
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun loadExistingPdfIfPresent(current: Inspection) {
        val service = reportService ?: return
        viewModelScope.launch {
            val candidate = service.capturedInspectionPdfFile(current)
            if (candidate.isFile && candidate.length() > 0L) {
                exportedPdfFile.value = candidate
                exportedPdfInfo.value = runCatching { service.inspect(candidate) }.getOrNull()
                pdfPreviewBitmap.value = runCatching {
                    service.renderPage(candidate, 0, PDF_PREVIEW_WIDTH_PX).asImageBitmap()
                }.getOrNull()
            }
        }
    }

    fun exportBrandedPdf() {
        val current = inspection.value ?: return
        val service = reportService ?: return
        if (exportingPdf.value) return
        exportingPdf.value = true
        val currentEquipment = equipmentDetails.value
        viewModelScope.launch {
            try {
                val file = service.exportCapturedInspectionPdf(
                    inspection = current,
                    equipmentDetails = currentEquipment,
                )
                exportedPdfFile.value = file
                exportedPdfInfo.value = runCatching { service.inspect(file) }.getOrNull()
                pdfPreviewBitmap.value = runCatching {
                    service.renderPage(file, 0, PDF_PREVIEW_WIDTH_PX).asImageBitmap()
                }.getOrNull()
                statusMessage.value = "Exported branded PDF (${currentEquipment.equipmentCode}): ${file.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusMessage.value = "Could not export PDF: ${e.message}"
            } finally {
                exportingPdf.value = false
            }
        }
    }

    fun savePdfCopy(context: Context, file: File, destination: Uri) {
        viewModelScope.launch {
            statusMessage.value = try {
                FileSharing.copyTo(context, file, destination)
                "Saved a copy of ${file.name}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save PDF copy: ${e.message}"
            }
        }
    }

    fun toggleTag(tag: String) {
        val current = inspection.value ?: return
        viewModelScope.launch {
            repository.toggleCapturedInspectionTag(current, tag)
        }
    }

    fun addCustomTag(rawTag: String = customTagInput.value) {
        val current = inspection.value ?: return
        val normalized = Inspection.normalizeTag(rawTag)
        if (normalized.isEmpty()) return
        customTagInput.value = ""
        viewModelScope.launch {
            repository.addCapturedInspectionTag(current, normalized)
        }
    }

    fun removeTag(tag: String) {
        val current = inspection.value ?: return
        viewModelScope.launch {
            repository.removeCapturedInspectionTag(current, tag)
        }
    }

    fun deleteInspection(onDeleted: () -> Unit) {
        viewModelScope.launch {
            reportService?.setSelectedCheckpointId(inspectionId, null)
            exportedPdfFile.value?.let { file ->
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
            }
            repository.deleteCapturedInspection(inspectionId)
            onDeleted()
        }
    }

    private companion object {
        const val PDF_PREVIEW_WIDTH_PX = 1080
    }
}

/**
 * Detail screen for a single camera-captured [Inspection] entity.
 * Displays the full-resolution image with pinch-to-zoom, capture timestamp, equipment details
 * (auto-linked or selectable from registered equipment), file metadata, preset + custom status
 * tag editing, branded PDFBox report export with in-app preview, and deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionDetailsScreen(
    container: AppContainer,
    inspectionId: Long,
    onBack: () -> Unit,
) {
    val activeInspector = container.auth.currentUser?.displayName
    val vm = viewModel(key = "inspection-details-$inspectionId") {
        InspectionDetailsViewModel(
            inspectionId = inspectionId,
            repository = container.repository,
            reportService = container.reportService,
            activeInspectorName = activeInspector,
        )
    }
    val item by vm.inspection.collectAsStateWithLifecycle()
    val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()
    val selectedCheckpointId by vm.selectedCheckpointId.collectAsStateWithLifecycle()
    val equipmentDetails by vm.equipmentDetails.collectAsStateWithLifecycle()
    val customTagInput by vm.customTagInput.collectAsStateWithLifecycle()
    val exportedPdfFile by vm.exportedPdfFile.collectAsStateWithLifecycle()
    val exportedPdfInfo by vm.exportedPdfInfo.collectAsStateWithLifecycle()
    val pdfPreviewBitmap by vm.pdfPreviewBitmap.collectAsStateWithLifecycle()
    val exportingPdf by vm.exportingPdf.collectAsStateWithLifecycle()
    val statusMessage by vm.statusMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val file = pendingSaveFile
        pendingSaveFile = null
        if (uri != null && file != null) {
            vm.savePdfCopy(context, file, uri)
        }
    }

    LaunchedEffect(item?.id) {
        item?.let { vm.loadExistingPdfIfPresent(it) }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.clearStatusMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Inspection Details",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("inspection-details-title"),
                        )
                        item?.let { current ->
                            Text(
                                text = "Capture #${current.id} · ${equipmentDetails.equipmentCode} · ${formatDateTime(current.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    BackButton(onBack = onBack)
                },
                actions = {
                    if (item != null) {
                        IconButton(
                            onClick = { vm.exportBrandedPdf() },
                            enabled = !exportingPdf,
                            modifier = Modifier.testTag("details-export-pdf-top"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_pdf),
                                contentDescription = "Export branded PDF",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.testTag("details-delete-button"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete captured inspection",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = item
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Captured inspection record not found.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = onBack) {
                        Text("Back to list")
                    }
                }
            }
            return@Scaffold
        }

        val file = remember(current.imagePath) { File(current.imagePath) }
        val decoded by produceState(
            initialValue = DecodedFullImage(
                bitmap = null,
                widthPx = 0,
                heightPx = 0,
                sizeBytes = 0L,
                existsOnDisk = false,
            ),
            current.imagePath,
        ) {
            value = withContext(Dispatchers.IO) {
                if (!file.isFile) {
                    DecodedFullImage(
                        bitmap = null,
                        widthPx = 1920,
                        heightPx = 1080,
                        sizeBytes = 0L,
                        existsOnDisk = false,
                    )
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val bmp = ImageUtils.decodeSampled(file, 2048)?.asImageBitmap()
                    DecodedFullImage(
                        bitmap = bmp,
                        widthPx = bounds.outWidth.takeIf { it > 0 } ?: (bmp?.width ?: 0),
                        heightPx = bounds.outHeight.takeIf { it > 0 } ?: (bmp?.height ?: 0),
                        sizeBytes = file.length(),
                        existsOnDisk = true,
                    )
                }
            }
        }

        val activeTags = current.normalizedTags.ifEmpty { listOf(Inspection.TAG_NEEDS_REVIEW) }
        val customTags = activeTags.filterNot { active ->
            Inspection.DEFAULT_STATUS_TAGS.any { it.equals(active, ignoreCase = true) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("inspection-details-screen"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Full-resolution pinch-to-zoom image viewer card
            ZoomableFullResolutionImageCard(
                inspection = current,
                decoded = decoded,
            )

            // 2. Equipment Details & Selector Card (Auto-linked or selectable registered equipment)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details-equipment-card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Equipment Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        StatusChip(
                            text = if (selectedCheckpointId == null) "Auto-linked" else "Selected",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("details-equipment-link-mode"),
                        )
                    }

                    DetailRow(
                        label = "Equipment",
                        value = equipmentDetails.equipmentName,
                        valueTestTag = "details-equipment-name",
                    )
                    DetailRow(
                        label = "Equipment code",
                        value = equipmentDetails.equipmentCode,
                        valueTestTag = "details-equipment-code",
                    )
                    DetailRow(
                        label = "Category",
                        value = equipmentDetails.equipmentCategory,
                        valueTestTag = "details-equipment-category",
                    )
                    DetailRow(
                        label = "Location",
                        value = equipmentDetails.locationPath,
                        valueTestTag = "details-equipment-location",
                    )
                    DetailRow(
                        label = "Inspector",
                        value = equipmentDetails.inspectorName,
                        valueTestTag = "details-equipment-inspector",
                    )

                    if (checkpoints.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Link to registered equipment (included in PDFBox report):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedCheckpointId == null,
                                onClick = { vm.selectEquipment(null) },
                                label = { Text("Auto-link") },
                                modifier = Modifier.testTag("details-select-equipment-auto"),
                            )
                            checkpoints.forEach { cp ->
                                FilterChip(
                                    selected = selectedCheckpointId == cp.id ||
                                        (selectedCheckpointId == null && equipmentDetails.checkpointId == cp.id),
                                    onClick = { vm.selectEquipment(cp.id) },
                                    label = { Text("${cp.code} · ${cp.name}") },
                                    modifier = Modifier.testTag("details-select-equipment-${cp.code}"),
                                )
                            }
                        }
                    }
                }
            }

            // 3. Timestamp & file details card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details-metadata-card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Capture & File Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DetailRow(
                        label = "Captured at",
                        value = formatDateTime(current.timestamp),
                        valueTestTag = "details-timestamp",
                    )
                    DetailRow(
                        label = "File name",
                        value = file.name.ifBlank { current.imagePath },
                        valueTestTag = "details-file-name",
                    )
                    DetailRow(
                        label = "Storage path",
                        value = current.imagePath,
                        valueTestTag = "details-image-path",
                    )
                    DetailRow(
                        label = "Resolution",
                        value = if (decoded.widthPx > 0 && decoded.heightPx > 0) {
                            "${decoded.widthPx} × ${decoded.heightPx} px"
                        } else {
                            "Full resolution"
                        },
                        valueTestTag = "details-resolution",
                    )
                    DetailRow(
                        label = "File size",
                        value = if (decoded.existsOnDisk && decoded.sizeBytes > 0L) {
                            String.format(Locale.US, "%.1f KB (%d bytes)", decoded.sizeBytes / 1024.0, decoded.sizeBytes)
                        } else {
                            "Seeded sample inspection photo"
                        },
                        valueTestTag = "details-file-size",
                    )
                }
            }

            // 4. Status & custom tag editor card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details-tags-card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Status Tags",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Tap preset status chips to toggle tags or add a custom tag below. Changes save immediately offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Active tag badges
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        activeTags.forEach { tag ->
                            StatusChip(
                                text = formatStatusTagLabel(tag),
                                color = statusTagColor(tag),
                                modifier = Modifier.testTag("details-active-tag-$tag"),
                            )
                        }
                    }

                    // Preset status tag chips
                    Text(
                        text = "Preset status tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Inspection.DEFAULT_STATUS_TAGS.forEach { tag ->
                            FilterChip(
                                selected = current.hasTag(tag),
                                onClick = { vm.toggleTag(tag) },
                                label = { Text(formatStatusTagLabel(tag)) },
                                modifier = Modifier.testTag("details-toggle-tag-$tag"),
                            )
                        }
                    }

                    // Custom tags list (if any)
                    if (customTags.isNotEmpty()) {
                        Text(
                            text = "Custom tags (tap to remove)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            customTags.forEach { tag ->
                                AssistChip(
                                    onClick = { vm.removeTag(tag) },
                                    label = { Text("${formatStatusTagLabel(tag)} ×") },
                                    modifier = Modifier.testTag("details-custom-tag-$tag"),
                                )
                            }
                        }
                    }

                    // Add custom tag input row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customTagInput,
                            onValueChange = vm::updateCustomTagInput,
                            label = { Text("Add custom tag (e.g. LINE_4, URGENT)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("details-custom-tag-input"),
                        )
                        Button(
                            onClick = { vm.addCustomTag() },
                            enabled = customTagInput.isNotBlank(),
                            modifier = Modifier.testTag("details-add-tag-button"),
                        ) {
                            Text("Add tag")
                        }
                    }
                }
            }

            // 5. Branded PDF Export Card (PDFBox)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("details-pdf-card"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_pdf),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Branded PDF Export (PDFBox)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Exports the high-resolution image, inspection timestamp, equipment details (${equipmentDetails.equipmentCode}), and status tags into a branded A4 PDF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val pdfFile = exportedPdfFile
                    if (pdfFile != null && pdfFile.isFile) {
                        val info = exportedPdfInfo
                        Text(
                            text = "PDF ready: ${pdfFile.name}" +
                                (info?.let { "\n${it.pageCount} page${if (it.pageCount == 1) "" else "s"} · ${(it.sizeBytes + 1023) / 1024} KB · Equipment: ${equipmentDetails.equipmentCode}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("details-pdf-status"),
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    FileSharing.sharePdf(
                                        context,
                                        pdfFile,
                                        "SafeStitch Captured Inspection #C${current.id} (${equipmentDetails.equipmentCode})",
                                    )
                                },
                                modifier = Modifier.testTag("details-share-pdf"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_share),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Share PDF")
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingSaveFile = pdfFile
                                    savePdfLauncher.launch(pdfFile.name)
                                },
                                modifier = Modifier.testTag("details-save-pdf"),
                            ) {
                                Text("Save a copy…")
                            }
                            TextButton(
                                onClick = {
                                    if (!FileSharing.openPdf(context, pdfFile)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("No external PDF viewer installed.")
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("details-open-pdf"),
                            ) {
                                Text("Open in app")
                            }
                        }
                    }

                    Button(
                        onClick = { vm.exportBrandedPdf() },
                        enabled = !exportingPdf,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("details-export-pdf-button"),
                    ) {
                        if (exportingPdf) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Exporting Branded PDF…")
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_pdf),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (pdfFile != null && pdfFile.isFile) {
                                    "Re-export Branded PDF with Latest Details"
                                } else {
                                    "Export Branded PDF"
                                },
                            )
                        }
                    }

                    pdfPreviewBitmap?.let { previewBmp ->
                        Text(
                            text = "Exported PDF Preview (Page 1)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Image(
                                bitmap = previewBmp,
                                contentDescription = "Branded PDF preview",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        previewBmp.width.toFloat() / previewBmp.height.coerceAtLeast(1),
                                    )
                                    .background(Color.White)
                                    .testTag("details-pdf-preview"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete captured inspection?") },
            text = {
                Text("This removes the captured inspection record from the local database and deletes its photo file.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.deleteInspection(onDeleted = onBack)
                    },
                    modifier = Modifier.testTag("details-confirm-delete"),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Alias composable for callers referencing `CapturedInspectionDetailsScreen`. */
@Composable
fun CapturedInspectionDetailsScreen(
    container: AppContainer,
    inspectionId: Long,
    onBack: () -> Unit,
) {
    InspectionDetailsScreen(
        container = container,
        inspectionId = inspectionId,
        onBack = onBack,
    )
}

@Composable
private fun ZoomableFullResolutionImageCard(
    inspection: Inspection,
    decoded: DecodedFullImage,
) {
    var scale by rememberSaveable(inspection.id) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(inspection.id) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(inspection.id) { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("details-image-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = String.format(Locale.US, "Full-Resolution Image (%.1fx)", scale),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("details-zoom-label"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {
                            scale = (scale - 0.5f).coerceAtLeast(1f)
                            if (scale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        },
                        label = { Text("−") },
                        modifier = Modifier.testTag("details-zoom-out"),
                    )
                    AssistChip(
                        onClick = {
                            scale = (scale + 0.5f).coerceAtMost(4f)
                        },
                        label = { Text("+") },
                        modifier = Modifier.testTag("details-zoom-in"),
                    )
                    if (scale > 1.01f || offsetX != 0f || offsetY != 0f) {
                        AssistChip(
                            onClick = {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            },
                            label = { Text("Reset") },
                            modifier = Modifier.testTag("details-zoom-reset"),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0E1716))
                    .pointerInput(inspection.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = newScale
                            if (newScale > 1f) {
                                val maxOffset = 320f * (newScale - 1f)
                                offsetX = (offsetX + pan.x).coerceIn(-maxOffset, maxOffset)
                                offsetY = (offsetY + pan.y).coerceIn(-maxOffset, maxOffset)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .testTag("details-full-image"),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = decoded.bitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Full-resolution inspection photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                } else {
                    SampleInspectionCanvasPreview(
                        inspection = inspection,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                }
            }

            Text(
                text = "Pinch with two fingers or use + / − to inspect details up to 4.0× zoom.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SampleInspectionCanvasPreview(
    inspection: Inspection,
    modifier: Modifier = Modifier,
) {
    val accent = statusTagColor(inspection.normalizedTags.firstOrNull() ?: Inspection.TAG_NEEDS_REVIEW)
    val fileName = File(inspection.imagePath).name.ifBlank { inspection.imagePath }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(color = Color(0xFF10201E))
            val step = w / 8f
            for (i in 1..7) {
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(step * i, 0f),
                    end = Offset(step * i, h),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val frameWidth = w * 0.72f
            val frameHeight = h * 0.64f
            val left = (w - frameWidth) / 2f
            val top = (h - frameHeight) / 2f
            drawRoundRect(
                color = accent.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(frameWidth, frameHeight),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.25f),
                radius = minOf(frameWidth, frameHeight) * 0.28f,
                center = Offset(w / 2f, h / 2f),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_photo_camera),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = fileName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "1920 × 1080 px · Captured ${formatDateTime(inspection.timestamp)}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueTestTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(108.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .testTag(valueTestTag),
        )
    }
}
