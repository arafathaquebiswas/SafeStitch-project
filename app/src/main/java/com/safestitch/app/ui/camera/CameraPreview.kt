package com.safestitch.app.ui.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException

/**
 * Camera viewfinder bound to the current screen's lifecycle. [useCases] (QR analysis or photo
 * capture) are bound alongside the preview and unbound again when the screen goes away.
 */
@Composable
fun CameraPreview(
    useCases: List<UseCase>,
    modifier: Modifier = Modifier,
    onCameraReady: (Camera) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val preview = remember { Preview.Builder().build() }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(lifecycleOwner, useCases) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            providerHolder[0] = provider
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> throw IllegalStateException("This device has no usable camera.")
            }
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, *useCases.toTypedArray())
            onCameraReady(camera)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    DisposableEffect(lifecycleOwner, useCases) {
        onDispose {
            providerHolder[0]?.unbind(preview, *useCases.toTypedArray())
        }
    }
}
