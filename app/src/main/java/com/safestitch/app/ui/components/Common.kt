package com.safestitch.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.safestitch.app.R
import com.safestitch.app.domain.ItemResult
import com.safestitch.app.domain.Outcome
import com.safestitch.app.ui.theme.LocalStatusColors
import com.safestitch.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatDateTime(millis: Long): String =
    dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}

@Composable
fun outcomeColor(outcome: Outcome): Color = when (outcome) {
    Outcome.PASS -> LocalStatusColors.current.pass
    Outcome.FAIL -> LocalStatusColors.current.fail
    Outcome.IN_PROGRESS -> LocalStatusColors.current.draft
}

@Composable
fun resultColor(result: ItemResult): Color = when (result) {
    ItemResult.PASS -> LocalStatusColors.current.pass
    ItemResult.FAIL -> LocalStatusColors.current.fail
    ItemResult.NOT_APPLICABLE, ItemResult.UNANSWERED -> LocalStatusColors.current.neutral
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Square thumbnail of an inspection photo, decoded off the main thread. */
@Composable
fun PhotoThumbnail(file: File, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { ImageUtils.decodeSampled(file, 320)?.asImageBitmap() }
    }
    Box(
        modifier = modifier
            .size(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(it, contentDescription = "Inspection photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

/**
 * Shows [content] when the camera permission is granted; otherwise explains why it is needed
 * and offers to ask again or open system settings. Asks once automatically.
 */
@Composable
fun CameraPermissionGate(
    explanation: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LifecycleResumeEffect(Unit) {
        granted = hasCameraPermission(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA)
    }
    if (granted) {
        content()
    } else {
        Column(
            modifier = modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(40.dp))
            Text(explanation, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            if (asked) {
                OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
            }
        }
    }
}
