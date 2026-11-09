package com.safestitch.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.safestitch.app.R
import com.safestitch.app.data.db.AreaPath
import com.safestitch.app.domain.ActionStatus
import com.safestitch.app.domain.FindingDefaults
import com.safestitch.app.domain.Priority
import com.safestitch.app.ui.theme.LocalStatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatDate(millis: Long): String = dateFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun Modifier.optionalTestTag(tag: String): Modifier = if (tag.isEmpty()) this else testTag(tag)

data class SelectOption<T>(val value: T, val label: String)

/** Read-only text field that opens a dropdown menu (no experimental APIs). */
@Composable
fun <T> SelectField(
    label: String,
    selectedLabel: String,
    options: List<SelectOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { expanded = true }
                .optionalTestTag(testTag),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

/** A position in the factory > building > floor > area hierarchy; null means "any" / "not set". */
data class LocationSelection(
    val factoryId: Long? = null,
    val buildingId: Long? = null,
    val floorId: Long? = null,
    val areaId: Long? = null,
) {
    fun matches(path: AreaPath?): Boolean =
        (factoryId == null || path?.factoryId == factoryId) &&
            (buildingId == null || path?.buildingId == buildingId) &&
            (floorId == null || path?.floorId == floorId) &&
            (areaId == null || path?.areaId == areaId)

    val isEmpty: Boolean get() = this == LocationSelection()

    companion object {
        fun of(path: AreaPath?) = if (path == null) LocationSelection() else LocationSelection(path.factoryId, path.buildingId, path.floorId, path.areaId)
    }
}

/**
 * Cascading Factory / Building / Floor / Area pickers built from the existing area paths.
 * [noneLabel] is the label for "no choice" ("All" when filtering, "Not set" when assigning).
 */
@Composable
fun LocationSelector(
    paths: List<AreaPath>,
    selection: LocationSelection,
    onChange: (LocationSelection) -> Unit,
    noneLabel: String,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val factories = paths.distinctBy { it.factoryId }
        SelectField(
            label = "Factory",
            selectedLabel = factories.firstOrNull { it.factoryId == selection.factoryId }?.factoryName ?: noneLabel,
            options = listOf(SelectOption<Long?>(null, noneLabel)) + factories.map { SelectOption(it.factoryId, it.factoryName) },
            onSelect = { onChange(LocationSelection(factoryId = it)) },
            testTag = "$tagPrefix-factory",
        )
        if (selection.factoryId != null) {
            val buildings = paths.filter { it.factoryId == selection.factoryId }.distinctBy { it.buildingId }
            SelectField(
                label = "Building",
                selectedLabel = buildings.firstOrNull { it.buildingId == selection.buildingId }?.buildingName ?: noneLabel,
                options = listOf(SelectOption<Long?>(null, noneLabel)) + buildings.map { SelectOption(it.buildingId, it.buildingName) },
                onSelect = { onChange(selection.copy(buildingId = it, floorId = null, areaId = null)) },
                testTag = "$tagPrefix-building",
            )
        }
        if (selection.buildingId != null) {
            val floors = paths.filter { it.buildingId == selection.buildingId }.distinctBy { it.floorId }
            SelectField(
                label = "Floor",
                selectedLabel = floors.firstOrNull { it.floorId == selection.floorId }?.floorName ?: noneLabel,
                options = listOf(SelectOption<Long?>(null, noneLabel)) + floors.map { SelectOption(it.floorId, it.floorName) },
                onSelect = { onChange(selection.copy(floorId = it, areaId = null)) },
                testTag = "$tagPrefix-floor",
            )
        }
        if (selection.floorId != null) {
            val areas = paths.filter { it.floorId == selection.floorId }
            SelectField(
                label = "Area",
                selectedLabel = areas.firstOrNull { it.areaId == selection.areaId }?.areaName ?: noneLabel,
                options = listOf(SelectOption<Long?>(null, noneLabel)) + areas.map { SelectOption(it.areaId, it.areaName) },
                onSelect = { onChange(selection.copy(areaId = it)) },
                testTag = "$tagPrefix-area",
            )
        }
    }
}

@Composable
fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.CRITICAL -> LocalStatusColors.current.fail
    Priority.HIGH -> LocalStatusColors.current.draft
    Priority.MEDIUM -> MaterialTheme.colorScheme.primary
    Priority.LOW -> LocalStatusColors.current.neutral
}

@Composable
fun actionStatusColor(status: ActionStatus): Color = when (status) {
    ActionStatus.OPEN -> LocalStatusColors.current.fail
    ActionStatus.RESOLVED -> LocalStatusColors.current.draft
    ActionStatus.VERIFIED -> LocalStatusColors.current.pass
}

@Composable
fun PrioritySelector(selected: Priority?, onSelect: (Priority) -> Unit, tagPrefix: String, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.forEach { priority ->
            FilterChip(
                selected = selected == priority,
                onClick = { onSelect(priority) },
                label = { Text(priority.label) },
                modifier = Modifier.testTag("$tagPrefix-${priority.name}"),
            )
        }
    }
}

/** Button showing the due date; opens a date picker (today or later). Returns 23:59 local time of the chosen day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDateButton(dueAt: Long?, onPick: (Long) -> Unit, testTag: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.testTag(testTag)) {
        Text("Due " + (dueAt?.let(::formatDate) ?: "—"))
    }
    if (open) {
        val zone = ZoneId.systemDefault()
        val todayUtc = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initialUtc = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialUtc?.coerceAtLeast(todayUtc) ?: todayUtc,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        onPick(FindingDefaults.endOfDay(Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate(), zone))
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
