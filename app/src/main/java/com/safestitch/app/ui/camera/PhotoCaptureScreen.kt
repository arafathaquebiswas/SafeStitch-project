package com.safestitch.app.ui.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.PhotoStorage
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.CameraPermissionGate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class PhotoCaptureViewModel(
    private val inspectionId: Long,
    private val itemId: Long?,
    private val repository: InspectionRepository,
    val photoStorage: PhotoStorage,
) : ViewModel() {
    var subtitle by mutableStateOf("General photo")
        private set
    private val done = Channel<Unit>(Channel.BUFFERED)
    val doneEvents = done.receiveAsFlow()

    init {
        if (itemId != null) {
            viewModelScope.launch {
                repository.getInspection(inspectionId)?.items?.firstOrNull { it.id == itemId }?.let {
                    subtitle = "Item ${it.position + 1}: ${it.question}"
                }
            }
        }
    }

    fun onSaved(file: File) {
        viewModelScope.launch {
            repository.addPhoto(inspectionId, itemId, file.name)
            done.send(Unit)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCaptureScreen(container: AppContainer, inspectionId: Long, itemId: Long?, onDone: () -> Unit) {
    val vm = viewModel { PhotoCaptureViewModel(inspectionId, itemId, container.repository, container.photoStorage) }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var capturing by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(85)
            .build()
    }
    val useCases = remember { listOf(imageCapture) }

    LaunchedEffect(vm) { vm.doneEvents.collect { onDone() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text("Take photo")
                        Text(vm.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton(onDone) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CameraPermissionGate(
            explanation = "SafeStitch needs the camera to attach photos of problems to the inspection report.",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                CameraPreview(
                    useCases = useCases,
                    modifier = Modifier.fillMaxSize(),
                    onError = { scope.launch { snackbar.showSnackbar("Camera unavailable: ${it.message}") } },
                )
                LargeFloatingActionButton(
                    onClick = {
                        if (capturing) return@LargeFloatingActionButton
                        capturing = true
                        val file = vm.photoStorage.newPhotoFile()
                        view.display?.let { imageCapture.targetRotation = it.rotation }
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    vm.onSaved(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    file.delete()
                                    scope.launch { snackbar.showSnackbar("Could not take the photo: ${exception.message}") }
                                }
                            },
                        )
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp)
                        .testTag("shutter"),
                ) {
                    if (capturing) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = "Take photo", modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}
