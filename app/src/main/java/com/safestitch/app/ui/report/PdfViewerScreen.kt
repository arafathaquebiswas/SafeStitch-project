package com.safestitch.app.ui.report

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.pdf.PdfStyle
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.util.FileSharing
import java.io.File

/** In-app PDF viewer: works offline on every phone, even without a PDF app installed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(container: AppContainer, inspectionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val file by produceState<File?>(initialValue = null, inspectionId) {
        value = container.repository.getInspection(inspectionId)?.inspection?.reportFileName
            ?.let(container.reportService::reportFile)?.takeIf(File::isFile)
    }
    val pageCount by produceState(initialValue = -1, file) {
        value = file?.let { runCatching { container.reportService.inspect(it).pageCount }.getOrDefault(0) } ?: -1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file?.name ?: "PDF report", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    file?.let { f ->
                        IconButton(onClick = { FileSharing.sharePdf(context, f, "SafeStitch report") }) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = "Share PDF")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val f = file
        if (f == null || pageCount < 0) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (pageCount == -1 && f == null) CircularProgressIndicator() else Text("The PDF could not be opened.")
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(600, 1600)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("pdf-viewer"),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pageCount) { index -> PdfPage(container, f, index, widthPx) }
            }
        }
    }
}

@Composable
private fun PdfPage(container: AppContainer, file: File, index: Int, widthPx: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file, index, widthPx) {
        value = runCatching { container.reportService.renderPage(file, index, widthPx).asImageBitmap() }.getOrNull()
    }
    val ratio = PdfStyle.PAGE_WIDTH.toFloat() / PdfStyle.PAGE_HEIGHT
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White).testTag("pdf-page-$index"), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator()
        } else {
            Image(b, contentDescription = "PDF page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}
