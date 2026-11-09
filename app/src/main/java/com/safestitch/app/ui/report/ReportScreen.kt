package com.safestitch.app.ui.report

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.domain.InspectionSummary
import com.safestitch.app.pdf.PdfInfo
import com.safestitch.app.pdf.ReportService
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.actionStatusColor
import com.safestitch.app.ui.components.formatDate
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.components.outcomeColor
import com.safestitch.app.ui.components.priorityColor
import com.safestitch.app.util.FileSharing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ReportUiState(
    val details: InspectionWithDetails,
    val summary: InspectionSummary,
    val file: File?,
    val info: PdfInfo?,
)

class ReportViewModel(
    private val inspectionId: Long,
    private val repository: InspectionRepository,
    private val reportService: ReportService,
) : ViewModel() {
    val state: StateFlow<ReportUiState?> = repository.observeInspection(inspectionId)
        .map { details ->
            details?.let {
                val file = it.inspection.reportFileName?.let(reportService::reportFile)?.takeIf(File::isFile)
                val info = file?.let { f -> runCatching { reportService.inspect(f) }.getOrNull() }
                ReportUiState(it, it.summary(), file, info)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    fun loadPreview(file: File) {
        viewModelScope.launch {
            preview = runCatching { reportService.renderPage(file, 0, PREVIEW_WIDTH_PX).asImageBitmap() }.getOrNull()
        }
    }

    /** Re-creates the PDF, e.g. to include the latest corrective-action status. */
    fun regenerate() {
        if (working) return
        working = true
        viewModelScope.launch {
            try {
                val file = reportService.generateReport(inspectionId, completedAt = null)
                repository.setReportFile(inspectionId, file.name)
                message = "PDF created again with the latest corrective-action status."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Could not create the PDF: ${e.message}"
            } finally {
                working = false
            }
        }
    }

    fun saveCopy(context: android.content.Context, file: File, destination: Uri) {
        viewModelScope.launch {
            message = try {
                FileSharing.copyTo(context, file, destination)
                "Saved a copy of the PDF."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the copy: ${e.message}"
            }
        }
    }

    private companion object {
        const val PREVIEW_WIDTH_PX = 1080
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    container: AppContainer,
    inspectionId: Long,
    onBack: () -> Unit,
    onViewPdf: () -> Unit,
    onOpenAction: (Long) -> Unit,
) {
    val vm = viewModel { ReportViewModel(inspectionId, container.repository, container.reportService) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) vm.saveCopy(context, file, uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    val current = state
    LaunchedEffect(current?.file?.path, current?.file?.lastModified()) { current?.file?.let(vm::loadPreview) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inspection report") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SummaryCard(current)
            val file = current.file
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_pdf), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("PDF report", style = MaterialTheme.typography.titleMedium)
                    }
                    if (file != null) {
                        val info = current.info
                        Text(
                            "PDF ready: ${file.name}" + (info?.let { "\n${it.pageCount} page${if (it.pageCount == 1) "" else "s"} · ${(it.sizeBytes + 1023) / 1024} KB" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("pdf-status"),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onViewPdf, modifier = Modifier.testTag("open-pdf")) { Text("Open PDF") }
                            OutlinedButton(
                                onClick = { FileSharing.sharePdf(context, file, "SafeStitch report – ${current.details.checkpoint.name}") },
                                modifier = Modifier.testTag("share-pdf"),
                            ) {
                                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pendingSave = file; saveLauncher.launch(file.name) }, modifier = Modifier.testTag("save-pdf")) {
                                Text("Save a copy…")
                            }
                            TextButton(
                                onClick = {
                                    if (!FileSharing.openPdf(context, file)) {
                                        scope.launch { snackbar.showSnackbar("No other PDF app installed. Use Open PDF instead.") }
                                    }
                                },
                                modifier = Modifier.testTag("open-external"),
                            ) { Text("Other app") }
                            TextButton(onClick = vm::regenerate, enabled = !vm.working, modifier = Modifier.testTag("regenerate-pdf")) {
                                Text(if (vm.working) "Creating…" else "Create again")
                            }
                        }
                    } else {
                        Text("The PDF file is missing on this phone.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pdf-status"))
                        Button(onClick = vm::regenerate, enabled = !vm.working, modifier = Modifier.testTag("regenerate-pdf")) {
                            Text(if (vm.working) "Creating…" else "Create PDF again")
                        }
                    }
                }
            }
            if (current.details.actions.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Corrective actions (${current.details.actions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("report-actions-title"),
                        )
                        val itemsById = current.details.items.associateBy { it.id }
                        current.details.actions.sortedBy { it.id }.forEach { action ->
                            val item = itemsById[action.itemId]
                            Card(onClick = { onOpenAction(action.id) }, modifier = Modifier.fillMaxWidth().testTag("report-action-${action.id}")) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${(item?.position ?: 0) + 1}. ${item?.question.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        StatusChip(action.priority.label, priorityColor(action.priority))
                                        StatusChip(action.status.label, actionStatusColor(action.status))
                                        Text("Due ${formatDate(action.dueAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            vm.preview?.let { bitmap ->
                Text("Preview of page 1", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "First page of the PDF report",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).background(Color.White).testTag("pdf-preview"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: ReportUiState) {
    val summary = state.summary
    val inspection = state.details.inspection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.details.checkpoint.name, style = MaterialTheme.typography.titleLarge)
            Text("${state.details.checkpoint.code}  ·  ${state.details.checkpoint.type.label}", style = MaterialTheme.typography.bodySmall)
            if (inspection.siteName.isNotBlank()) Text(inspection.siteName, style = MaterialTheme.typography.bodySmall)
            Text(
                "Result: ${summary.outcome.label}",
                color = outcomeColor(summary.outcome),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("report-outcome"),
            )
            Text(
                "Score ${summary.scorePercent}% · ${summary.passed} passed · ${summary.failed} failed · ${summary.notApplicable} N/A" +
                    if (summary.criticalFailures > 0) " · ${summary.criticalFailures} critical failure(s)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Inspector: ${inspection.inspectorName}", style = MaterialTheme.typography.bodyMedium)
            inspection.completedAt?.let { Text("Submitted ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
