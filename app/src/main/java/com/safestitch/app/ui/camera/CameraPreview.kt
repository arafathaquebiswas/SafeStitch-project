package com.safestitch.app.ui.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.safestitch.app.R
import kotlinx.coroutines.CancellationException

/**
 * Real-time CameraX viewfinder bound to the current screen's lifecycle.
 * [useCases] (such as QR analysis or photo capture) are bound alongside the preview
 * and unbound automatically when the composable leaves composition.
 * When [showFramingOverlay] is true, draws a framing reticle guide so the user can
 * align and frame inspection items in real time.
 * When [onCaptureClick] is provided, renders a floating action button over the viewfinder
 * that triggers a photo capture when clicked.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    useCases: List<UseCase> = emptyList(),
    showFramingOverlay: Boolean = false,
    isCapturing: Boolean = false,
    onCaptureClick: (() -> Unit)? = null,
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

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (showFramingOverlay) {
            InspectionFramingOverlay(modifier = Modifier.fillMaxSize())
        }
        if (onCaptureClick != null) {
            FloatingActionButton(
                onClick = { if (!isCapturing) onCaptureClick() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .testTag("viewfinder-capture-fab"),
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_photo_camera),
                        contentDescription = "Capture inspection photo",
                    )
                }
            }
        }
    }

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
            provider.unbind(preview, *useCases.toTypedArray())
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

/**
 * Visual framing reticle drawn over the live CameraX viewfinder to help inspectors
 * center and frame equipment, gauges, and safety checkpoints.
 */
@Composable
fun InspectionFramingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val frameWidth = size.width * 0.76f
        val frameHeight = size.height * 0.68f
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f
        val right = left + frameWidth
        val bottom = top + frameHeight
        val cornerLen = minOf(frameWidth, frameHeight) * 0.16f
        val strokeWidth = 5f

        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(left, top),
            size = Size(frameWidth, frameHeight),
            cornerRadius = CornerRadius(20f, 20f),
            style = Stroke(width = 2f),
        )

        val bracketColor = Color(0xFFA6F0E8)
        // Top-left corner bracket
        drawLine(bracketColor, Offset(left, top + cornerLen), Offset(left, top), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth, StrokeCap.Round)
        // Top-right corner bracket
        drawLine(bracketColor, Offset(right - cornerLen, top), Offset(right, top), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right, top), Offset(right, top + cornerLen), strokeWidth, StrokeCap.Round)
        // Bottom-left corner bracket
        drawLine(bracketColor, Offset(left, bottom - cornerLen), Offset(left, bottom), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth, StrokeCap.Round)
        // Bottom-right corner bracket
        drawLine(bracketColor, Offset(right - cornerLen, bottom), Offset(right, bottom), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeWidth, StrokeCap.Round)

        // Center alignment crosshair
        val center = Offset(size.width / 2f, size.height / 2f)
        val crossRadius = 14f
        drawLine(Color.White.copy(alpha = 0.6f), Offset(center.x - crossRadius, center.y), Offset(center.x + crossRadius, center.y), 2f)
        drawLine(Color.White.copy(alpha = 0.6f), Offset(center.x, center.y - crossRadius), Offset(center.x, center.y + crossRadius), 2f)
    }
}
