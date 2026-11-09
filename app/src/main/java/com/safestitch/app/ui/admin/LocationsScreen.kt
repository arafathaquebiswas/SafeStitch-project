package com.safestitch.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.LocationLevel
import com.safestitch.app.data.LocationNode
import com.safestitch.app.ui.components.BackButton
import kotlinx.coroutines.launch

/** One level of the factory › building › floor › area tree. Tap a row to open the level below. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocationsScreen(
    container: AppContainer,
    level: LocationLevel,
    parentId: Long,
    title: String,
    onBack: () -> Unit,
    onOpenChild: (LocationLevel, Long, String) -> Unit,
) {
    val nodes by remember(level, parentId) { container.catalog.observeChildren(level, parentId) }.collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LocationNode?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (level == LocationLevel.FACTORY) "Locations" else "$title › ${level.plural}") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text("Add ${level.label.lowercase()}") },
                modifier = Modifier.testTag("add-location"),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Factory › building › floor › area. Equipment is assigned to an area. " +
                        "Renaming keeps past inspection reports unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (nodes.isEmpty()) {
                item { Text("No ${level.plural.lowercase()} yet. Tap \"Add ${level.label.lowercase()}\".", modifier = Modifier.padding(vertical = 12.dp)) }
            }
            items(nodes, key = { it.id }) { node ->
                val child = level.child
                Card(
                    onClick = { if (child != null) onOpenChild(child, node.id, "$title › ${node.name}".removePrefix("Factories › ")) },
                    modifier = Modifier.fillMaxWidth().testTag("location-${node.name}"),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = node }) { Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename ${node.name}") }
                        if (child != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Open ${child.plural.lowercase()}")
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        val node = editing
        var name by rememberSaveable(node?.id) { mutableStateOf(node?.name.orEmpty()) }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (node == null) "Add ${level.label.lowercase()}" else "Rename ${level.label.lowercase()}") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("${level.label} name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("location-name-field"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            if (node == null) container.catalog.addLocation(level, parentId, name) else container.catalog.renameLocation(level, node.id, name)
                            adding = false
                            editing = null
                        }
                    },
                    modifier = Modifier.testTag("location-save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
}
