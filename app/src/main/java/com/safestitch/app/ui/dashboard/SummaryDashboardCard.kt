package com.safestitch.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safestitch.app.data.InspectionRepository
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.data.db.CheckpointEntity
import com.safestitch.app.data.db.Inspection
import com.safestitch.app.data.db.InspectionWithDetails
import com.safestitch.app.domain.Outcome
import com.safestitch.app.ui.components.StatusChip
import com.safestitch.app.ui.components.formatDateTime
import java.io.File
import java.util.Locale

/**
 * Single row in the "Recent Equipment Status Updates" feed on the homepage summary dashboard.
 */
data class EquipmentStatusUpdateItem(
    val key: String,
    val equipmentCode: String,
    val equipmentName: String,
    val categoryOrLocation: String,
    val statusLabel: String,
    val statusKind: EquipmentStatusKind,
    val timestampMillis: Long,
    val isCapturedPhoto: Boolean,
    val recordId: Long,
    val isCompletedChecklist: Boolean = false,
)

enum class EquipmentStatusKind {
    PASS,
    FAIL,
    PENDING,
}

/**
 * Aggregated homepage summary metrics and recent equipment status updates.
 */
data class SummaryDashboardMetrics(
    val totalInspectionsPerformed: Int,
    val checklistInspectionsCount: Int,
    val capturedPhotosCount: Int,
    val pendingReportsCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val recentUpdates: List<EquipmentStatusUpdateItem>,
)

object SummaryDashboardCalculator {
    fun compute(
        inspections: List<InspectionWithDetails>,
        capturedInspections: List<Inspection>,
        checkpoints: List<CheckpointEntity> = emptyList(),
        areaPaths: List<AreaPath> = emptyList(),
        exportedReportFiles: Set<String> = emptySet(),
        maxRecentItems: Int = 4,
    ): SummaryDashboardMetrics {
        val checklistTotal = inspections.size
        val capturedTotal = capturedInspections.size
        val totalPerformed = checklistTotal + capturedTotal

        // Checklist breakdown
        var passed = 0
        var failed = 0
        var pendingReports = 0

        inspections.forEach { details ->
            val summary = details.summary()
            val hasReport = !details.inspection.reportFileName.isNullOrBlank()
            if (!details.isCompleted || !hasReport) {
                pendingReports += 1
            }
            when (summary.outcome) {
                Outcome.PASS -> passed += 1
                Outcome.FAIL -> failed += 1
                Outcome.IN_PROGRESS -> Unit
            }
        }

        // Captured inspections breakdown
        capturedInspections.forEach { cap ->
            val tags = cap.normalizedTags
            val hasExportedPdf = exportedReportFiles.contains("C-${cap.id}")
            val isPendingTag = tags.any {
                it.equals(Inspection.TAG_NEEDS_REVIEW, ignoreCase = true) ||
                    it.equals(Inspection.TAG_FOLLOW_UP, ignoreCase = true)
            }
            if (isPendingTag && !hasExportedPdf) {
                pendingReports += 1
            }
            when {
                tags.any { it.equals(Inspection.TAG_FAIL, ignoreCase = true) } -> failed += 1
                tags.any { it.equals(Inspection.TAG_PASS, ignoreCase = true) } -> passed += 1
            }
        }

        val checklistUpdates = inspections.map { details ->
            val summary = details.summary()
            val statusKind = when (summary.outcome) {
                Outcome.PASS -> EquipmentStatusKind.PASS
                Outcome.FAIL -> EquipmentStatusKind.FAIL
                Outcome.IN_PROGRESS -> EquipmentStatusKind.PENDING
            }
            val statusLabel = when {
                !details.isCompleted -> "PENDING REPORT"
                summary.outcome == Outcome.PASS -> "PASS (${summary.scorePercent}%)"
                summary.outcome == Outcome.FAIL -> "FAIL (${summary.scorePercent}%)"
                else -> summary.outcome.label.uppercase(Locale.ROOT)
            }
            val timestamp = details.inspection.completedAt ?: details.inspection.startedAt
            val location = details.inspection.siteName.ifBlank { details.checkpoint.area }.ifBlank { details.equipmentType.label }
            EquipmentStatusUpdateItem(
                key = "insp-${details.inspection.id}",
                equipmentCode = details.equipmentCode,
                equipmentName = details.equipmentName,
                categoryOrLocation = location,
                statusLabel = statusLabel,
                statusKind = statusKind,
                timestampMillis = timestamp,
                isCapturedPhoto = false,
                recordId = details.inspection.id,
                isCompletedChecklist = details.isCompleted,
            )
        }

        val capturedUpdates = capturedInspections.map { cap ->
            val resolved = InspectionRepository.resolveEquipmentFromCatalog(
                inspection = cap,
                allCheckpoints = checkpoints,
                areaPaths = areaPaths,
                allInspections = inspections,
            )
            val primaryTag = cap.normalizedTags.firstOrNull() ?: Inspection.TAG_NEEDS_REVIEW
            val statusKind = when (primaryTag.uppercase(Locale.ROOT)) {
                Inspection.TAG_PASS -> EquipmentStatusKind.PASS
                Inspection.TAG_FAIL -> EquipmentStatusKind.FAIL
                else -> EquipmentStatusKind.PENDING
            }
            EquipmentStatusUpdateItem(
                key = "cap-${cap.id}",
                equipmentCode = resolved.equipmentCode,
                equipmentName = resolved.equipmentName,
                categoryOrLocation = "${resolved.equipmentCategory} · ${File(cap.imagePath).name.ifBlank { "Captured photo" }}",
                statusLabel = primaryTag.replace('_', ' '),
                statusKind = statusKind,
                timestampMillis = cap.timestamp,
                isCapturedPhoto = true,
                recordId = cap.id,
            )
        }

        val recent = (checklistUpdates + capturedUpdates)
            .sortedByDescending { it.timestampMillis }
            .take(maxRecentItems)

        return SummaryDashboardMetrics(
            totalInspectionsPerformed = totalPerformed,
            checklistInspectionsCount = checklistTotal,
            capturedPhotosCount = capturedTotal,
            pendingReportsCount = pendingReports,
            passedCount = passed,
            failedCount = failed,
            recentUpdates = recent,
        )
    }
}

/**
 * Homepage Summary Dashboard Card displaying:
 * 1. Key inspection metrics (Total Inspections Performed, Passed, Failed, Pending Reports).
 * 2. A combined Donut + Horizontal Bar Data Visualization Chart.
 * 3. Recent Equipment Status Updates feed with one-tap navigation.
 */
@Composable
fun SummaryDashboardCard(
    metrics: SummaryDashboardMetrics,
    onOpenChecklistInspection: (inspectionId: Long, completed: Boolean) -> Unit,
    onOpenCapturedInspection: (capturedId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val passColor = Color(0xFF1B7F3B)
    val failColor = Color(0xFFB3261E)
    val pendingColor = Color(0xFFD97706)
    val brandTeal = Color(0xFF0B6E69)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("summary-dashboard-card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inspection Summary Overview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("summary-dashboard-title"),
                    )
                    Text(
                        text = "${metrics.checklistInspectionsCount} checklists · ${metrics.capturedPhotosCount} camera captures",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = if (metrics.pendingReportsCount > 0) pendingColor.copy(alpha = 0.14f) else passColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "${metrics.pendingReportsCount} Pending Report${if (metrics.pendingReportsCount == 1) "" else "s"}",
                        color = if (metrics.pendingReportsCount > 0) pendingColor else passColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("summary-pending-reports-badge"),
                    )
                }
            }

            // Key Metric Highlight Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetricTile(
                    label = "Total Performed",
                    value = metrics.totalInspectionsPerformed.toString(),
                    accentColor = brandTeal,
                    valueTag = "summary-metric-total",
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricTile(
                    label = "Passed",
                    value = metrics.passedCount.toString(),
                    accentColor = passColor,
                    valueTag = "summary-metric-passed",
                    modifier = Modifier.weight(1f),
                )
                SummaryMetricTile(
                    label = "Pending Reports",
                    value = metrics.pendingReportsCount.toString(),
                    accentColor = pendingColor,
                    valueTag = "summary-metric-pending-reports",
                    modifier = Modifier.weight(1f),
                )
            }

            // Data Visualization Chart (Donut Ring + Proportional Horizontal Bars)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary-dashboard-chart"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Donut Chart
                    Box(
                        modifier = Modifier.size(104.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(96.dp)) {
                            val strokeWidth = 14.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                            val arcSize = Size(diameter, diameter)

                            // Background track
                            drawArc(
                                color = Color.Gray.copy(alpha = 0.2f),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                            )

                            val segments = listOf(
                                metrics.passedCount.coerceAtLeast(0) to passColor,
                                metrics.failedCount.coerceAtLeast(0) to failColor,
                                metrics.pendingReportsCount.coerceAtLeast(0) to pendingColor,
                            )
                            val sum = segments.sumOf { it.first }.coerceAtLeast(1)
                            var startAngle = -90f
                            segments.forEach { (count, color) ->
                                if (count > 0) {
                                    val sweep = (count.toFloat() / sum.toFloat()) * 360f
                                    drawArc(
                                        color = color,
                                        startAngle = startAngle,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                                    )
                                    startAngle += sweep
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = metrics.totalInspectionsPerformed.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Total",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Proportional Bar Visualization + Legend
                    val maxBar = maxOf(
                        metrics.totalInspectionsPerformed,
                        metrics.passedCount,
                        metrics.failedCount,
                        metrics.pendingReportsCount,
                        1,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChartBarRow(
                            label = "Passed",
                            count = metrics.passedCount,
                            fraction = metrics.passedCount.toFloat() / maxBar,
                            color = passColor,
                        )
                        ChartBarRow(
                            label = "Failed / Alerts",
                            count = metrics.failedCount,
                            fraction = metrics.failedCount.toFloat() / maxBar,
                            color = failColor,
                        )
                        ChartBarRow(
                            label = "Pending Reports",
                            count = metrics.pendingReportsCount,
                            fraction = metrics.pendingReportsCount.toFloat() / maxBar,
                            color = pendingColor,
                        )
                    }
                }
            }

            // Recent Equipment Status Updates
            if (metrics.recentUpdates.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Recent Equipment Status Updates",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("summary-recent-updates-header"),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("summary-recent-updates-list"),
                ) {
                    metrics.recentUpdates.forEach { update ->
                        val chipColor = when (update.statusKind) {
                            EquipmentStatusKind.PASS -> passColor
                            EquipmentStatusKind.FAIL -> failColor
                            EquipmentStatusKind.PENDING -> pendingColor
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .clickable {
                                    if (update.isCapturedPhoto) {
                                        onOpenCapturedInspection(update.recordId)
                                    } else {
                                        onOpenChecklistInspection(update.recordId, update.isCompletedChecklist)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("summary-recent-item-${update.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Surface(
                                        color = brandTeal.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = update.equipmentCode,
                                            color = brandTeal,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = update.equipmentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = "${update.categoryOrLocation} · ${formatDateTime(update.timestampMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusChip(
                                text = update.statusLabel,
                                color = chipColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricTile(
    label: String,
    value: String,
    accentColor: Color,
    valueTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = accentColor.copy(alpha = 0.09f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                modifier = Modifier.testTag(valueTag),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChartBarRow(
    label: String,
    count: Int,
    fraction: Float,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.18f),
                size = size,
                cornerRadius = radius,
            )
            val fillWidth = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (count > 0) 6.dp.toPx() else 0f)
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(fillWidth.coerceAtMost(size.width), size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}
