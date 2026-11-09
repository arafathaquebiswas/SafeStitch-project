package com.safestitch.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.db.TemplateQuestionEntity
import com.safestitch.app.domain.CheckpointType
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** Admin editor for the checklist questions of each equipment type. Changes apply to new inspections only. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChecklistEditorScreen(container: AppContainer, onBack: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(CheckpointType.FIRE_EXTINGUISHER) }
    val questions by remember(type) { container.catalog.observeQuestions(type) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<TemplateQuestionEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Checklist questions") }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text("Add question") },
                modifier = Modifier.testTag("add-question"),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CheckpointType.entries) { t ->
                        FilterChip(selected = t == type, onClick = { type = t }, label = { Text(t.label + if (t.legacy) " (earlier)" else "") }, modifier = Modifier.testTag("type-${t.name}"))
                    }
                }
            }
            item {
                Text(
                    "Changes apply to new inspections. Inspections already started or submitted keep the questions they had. " +
                        "Switch a question off instead of deleting it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(questions, key = { _, q -> q.id }) { index, q ->
                Card(Modifier.fillMaxWidth().testTag("question-$index")) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp).alpha(if (q.active) 1f else 0.5f)) {
                        Text(q.section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("${index + 1}. ${q.question}", style = MaterialTheme.typography.bodyLarge)
                        if (q.critical) Text("Critical", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.fail)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { container.catalog.moveQuestion(q, up = true) } }, enabled = index > 0) {
                                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "Move up")
                            }
                            IconButton(onClick = { scope.launch { container.catalog.moveQuestion(q, up = false) } }, enabled = index < questions.lastIndex) {
                                Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "Move down")
                            }
                            IconButton(onClick = { editing = q }, modifier = Modifier.testTag("question-$index-edit")) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit question")
                            }
                            Text(if (q.active) "In use" else "Switched off", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Switch(
                                checked = q.active,
                                onCheckedChange = { active -> scope.launch { container.catalog.updateQuestion(q.copy(active = active)) } },
                                modifier = Modifier.testTag("question-$index-active"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        val existing = editing
        var section by rememberSaveable(existing?.id) { mutableStateOf(existing?.section ?: questions.lastOrNull()?.section.orEmpty()) }
        var text by rememberSaveable(existing?.id) { mutableStateOf(existing?.question.orEmpty()) }
        var critical by rememberSaveable(existing?.id) { mutableStateOf(existing?.critical ?: false) }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (existing == null) "Add question (${type.label})" else "Edit question") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = section,
                        onValueChange = { section = it.take(60) },
                        label = { Text("Section") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("question-section"),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(300) },
                        label = { Text("Question") },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("question-text"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = critical, onCheckedChange = { critical = it }, modifier = Modifier.testTag("question-critical"))
                        Text("Critical (a fail fails the whole checkpoint)")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = section.isNotBlank() && text.isNotBlank(),
                    onClick = {
                        scope.launch {
                            if (existing == null) {
                                container.catalog.addQuestion(type, section, text, critical)
                            } else {
                                container.catalog.updateQuestion(existing.copy(section = section, question = text, critical = critical))
                            }
                            adding = false
                            editing = null
                        }
                    },
                    modifier = Modifier.testTag("question-save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}
