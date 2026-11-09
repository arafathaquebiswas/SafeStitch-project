package com.safestitch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.AppContainer
import com.safestitch.app.BuildConfig
import com.safestitch.app.ui.components.BackButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val saved by container.settings.settings.collectAsStateWithLifecycle()
    var inspector by rememberSaveable { mutableStateOf(saved.inspectorName) }
    var site by rememberSaveable { mutableStateOf(saved.siteName) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Defaults for new inspections", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = inspector,
                onValueChange = { inspector = it.take(80) },
                label = { Text("Inspector name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().testTag("settings-inspector"),
            )
            OutlinedTextField(
                value = site,
                onValueChange = { site = it.take(80) },
                label = { Text("Site / factory") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().testTag("settings-site"),
            )
            Button(
                onClick = {
                    container.settings.update(inspector, site)
                    scope.launch { snackbar.showSnackbar("Saved") }
                },
                modifier = Modifier.fillMaxWidth().testTag("settings-save"),
            ) { Text("Save") }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About SafeStitch", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", modifier = Modifier.testTag("about-version"))
                    Text("App ID ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "SafeStitch has no internet permission. Checkpoints, inspections, photos and PDFs stay on this phone " +
                            "until you choose to share or save a report.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
