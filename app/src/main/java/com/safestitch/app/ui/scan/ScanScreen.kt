package com.safestitch.app.ui.scan

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.QrPayload
import com.safestitch.app.qr.QrCodeAnalyzer
import com.safestitch.app.ui.camera.CameraPreview
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.CameraPermissionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ScanViewModel(
    private val repository: InspectionRepository,
    private val user: UserEntity,
) : ViewModel() {
    sealed interface Event {
        data class InspectionReady(val inspectionId: Long) : Event
        data class NewCheckpoint(val code: String) : Event
        data class Invalid(val message: String) : Event
    }

    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    var busy by mutableStateOf(false)
        private set

    fun onCode(raw: String) {
        if (busy) return
        val code = QrPayload.parseCheckpointCode(raw)
        if (code == null) {
            viewModelScope.launch { channel.send(Event.Invalid("That QR code does not contain a usable checkpoint code.")) }
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val checkpoint = repository.findCheckpointByCode(code)
                if (checkpoint == null) {
                    channel.send(Event.NewCheckpoint(code))
                } else {
                    channel.send(Event.InspectionReady(repository.startOrResumeInspection(checkpoint.id, user.displayName, user.id)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.send(Event.Invalid("Could not open the checkpoint: ${e.message}"))
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    container: AppContainer,
    user: UserEntity,
    onBack: () -> Unit,
    onInspectionReady: (Long) -> Unit,
    onNewCheckpoint: (String) -> Unit,
) {
    val vm = viewModel { ScanViewModel(container.repository, user) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var manualCode by rememberSaveable { mutableStateOf("") }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analyzer = remember { QrCodeAnalyzer { text -> mainExecutor.execute { vm.onCode(text) } } }
    val analysis = remember {
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
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
    }
    val useCases = remember { listOf(analysis) }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ScanViewModel.Event.InspectionReady -> onInspectionReady(event.inspectionId)
                is ScanViewModel.Event.NewCheckpoint -> onNewCheckpoint(event.code)
                is ScanViewModel.Event.Invalid -> {
                    scope.launch { snackbar.showSnackbar(event.message) }
                    delay(1_500)
                    analyzer.resume()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan checkpoint") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    val cam = camera
                    if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                        IconButton(onClick = {
                            torchOn = !torchOn
                            cam.cameraControl.enableTorch(torchOn)
                        }) {
                            Icon(
                                painterResource(if (torchOn) R.drawable.ic_flash_off else R.drawable.ic_flash_on),
                                contentDescription = if (torchOn) "Turn torch off" else "Turn torch on",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                CameraPermissionGate(
                    explanation = "Allow the camera to scan checkpoint QR labels. You can also type the code below.",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clipToBounds(),
                    ) {
                        CameraPreview(
                            useCases = useCases,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("scan-preview"),
                            onCameraReady = { camera = it },
                            onError = { scope.launch { snackbar.showSnackbar("Camera unavailable: ${it.message}") } },
                        )
                        ViewfinderOverlay(Modifier.fillMaxSize())
                    }
                }
            }
            Text(
                "Point the camera at the checkpoint's QR label. It opens the checklist automatically.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it.take(QrPayload.MAX_CODE_LENGTH) },
                label = { Text("Or type the checkpoint code") },
                placeholder = { Text("e.g. SS-LINE-04") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (manualCode.isNotBlank()) vm.onCode(manualCode) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-code"),
            )
            Button(
                onClick = { vm.onCode(manualCode) },
                enabled = manualCode.isNotBlank() && !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-continue"),
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.62f
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
        drawRoundRect(
            color = Color.White,
            topLeft = topLeft,
            size = GeometrySize(side, side),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 6f),
        )
    }
}
