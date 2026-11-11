package com.safestitch.app.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.PhotoThumbnail
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class CapturedInspectionsViewModel(private val repository: InspectionRepository) : ViewModel() {
    val selectedTag: MutableStateFlow<String?> = MutableStateFlow(null)

    val allInspections: StateFlow<List<Inspection>> = repository
        .observeCapturedInspections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredInspections: StateFlow<List<Inspection>> = combine(
        allInspections,
        selectedTag,
    ) { list, tag ->
        if (tag == null) list else list.filter { it.hasTag(tag) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTagFilter(tag: String?) {
        selectedTag.value = if (selectedTag.value == tag) null else tag
    }

    fun toggleTag(inspection: Inspection, tag: String) {
        viewModelScope.launch {
            repository.toggleCapturedInspectionTag(inspection, tag)
        }
    }

    fun deleteInspection(id: Long) {
        viewModelScope.launch {
            repository.deleteCapturedInspection(id)
        }
    }
}

/**
 * Screen listing captured [Inspection] entities from the Room database, displaying a thumbnail
 * of the captured image, the formatted timestamp, and the associated status tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturedInspectionsScreen(
    container: AppContainer,
    onBack: (() -> Unit)? = null,
    onOpenInspection: (Long) -> Unit = {},
) {
    val vm = viewModel { CapturedInspectionsViewModel(container.repository) }
    val all by vm.allInspections.collectAsStateWithLifecycle()
    val shown by vm.filteredInspections.collectAsStateWithLifecycle()
    val selectedTag by vm.selectedTag.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Captured Inspections", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${shown.size} of ${all.size} captured photo${if (all.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("captured-inspections-count"),
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        BackButton(onBack = onBack)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("captured-inspections-list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("captured-tag-filters"),
                ) {
                    item {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { vm.selectTagFilter(null) },
                            label = { Text("All (${all.size})") },
                            modifier = Modifier.testTag("captured-filter-ALL"),
                        )
                    }
                    items(Inspection.DEFAULT_STATUS_TAGS) { tag ->
                        val count = all.count { it.hasTag(tag) }
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { vm.selectTagFilter(tag) },
                            label = { Text("${formatStatusTagLabel(tag)} ($count)") },
                            modifier = Modifier.testTag("captured-filter-$tag"),
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("captured-inspections-empty"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_photo_camera),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = if (selectedTag == null) {
                                    "No captured inspection photos yet"
                                } else {
                                    "No captured photos with tag ${formatStatusTagLabel(selectedTag!!)}"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Use the camera capture button on the main screen viewfinder to capture and tag inspection photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(shown, key = { it.id }) { inspection ->
                    CapturedInspectionCard(
                        inspection = inspection,
                        onClick = { onOpenInspection(inspection.id) },
                        onToggleTag = { tag -> vm.toggleTag(inspection, tag) },
                        onDelete = { vm.deleteInspection(inspection.id) },
                    )
                }
            }
        }
    }
}

/** Alias composable for callers referencing `InspectionListScreen`. */
@Composable
fun InspectionListScreen(
    container: AppContainer,
    onBack: (() -> Unit)? = null,
    onOpenInspection: (Long) -> Unit = {},
) {
    CapturedInspectionsScreen(
        container = container,
        onBack = onBack,
        onOpenInspection = onOpenInspection,
    )
}

@Composable
fun CapturedInspectionCard(
    inspection: Inspection,
    onToggleTag: (String) -> Unit,
    onClick: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val file = File(inspection.imagePath)
    val tags = inspection.normalizedTags.ifEmpty { listOf(Inspection.TAG_NEEDS_REVIEW) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("captured-inspection-${inspection.id}"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PhotoThumbnail(
                    file = file,
                    onClick = onClick,
                    modifier = Modifier.testTag("captured-thumbnail-${inspection.id}"),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formatDateTime(inspection.timestamp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("captured-timestamp-${inspection.id}"),
                    )
                    Text(
                        text = file.name.ifBlank { inspection.imagePath },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("captured-path-${inspection.id}"),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            StatusChip(
                                text = formatStatusTagLabel(tag),
                                color = statusTagColor(tag),
                                modifier = Modifier.testTag("captured-tag-${inspection.id}-$tag"),
                            )
                        }
                    }
                }
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("captured-delete-${inspection.id}"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = "Delete captured inspection",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Tags:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Inspection.DEFAULT_STATUS_TAGS.forEach { tag ->
                    FilterChip(
                        selected = inspection.hasTag(tag),
                        onClick = { onToggleTag(tag) },
                        label = {
                            Text(
                                text = formatStatusTagLabel(tag),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.testTag("toggle-tag-${inspection.id}-$tag"),
                    )
                }
            }
        }
    }
}

@Composable
fun statusTagColor(tag: String): Color {
    val colors = LocalStatusColors.current
    return when (tag.trim().uppercase()) {
        Inspection.TAG_PASS -> colors.pass
        Inspection.TAG_FAIL -> colors.fail
        Inspection.TAG_NEEDS_REVIEW, Inspection.TAG_FOLLOW_UP -> colors.draft
        else -> colors.neutral
    }
}

fun formatStatusTagLabel(tag: String): String =
    tag.trim().replace('_', ' ')
