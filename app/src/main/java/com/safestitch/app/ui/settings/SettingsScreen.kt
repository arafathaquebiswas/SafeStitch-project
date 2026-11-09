package com.safestitch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.safestitch.app.AppContainer
import com.safestitch.app.BuildConfig
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.ui.auth.PasswordField
import com.safestitch.app.ui.components.BackButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, user: UserEntity, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var changing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleMedium)
                    Text("${user.displayName} (@${user.username})", modifier = Modifier.testTag("settings-user"))
                    Text("Role: ${user.role.label}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { changing = true }, modifier = Modifier.testTag("change-password")) { Text("Change my password") }
                    OutlinedButton(onClick = { container.auth.signOut() }, modifier = Modifier.testTag("settings-signout")) { Text("Sign out") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About SafeStitch", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", modifier = Modifier.testTag("about-version"))
                    Text("App ID ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Everything works offline and is stored on this phone. The internet is used only if an admin turns on " +
                            "sync (Admin › Sync), and then only to upload inspection data and corrective actions.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (changing) {
        var current by rememberSaveable { mutableStateOf("") }
        var new by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { changing = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(current, { current = it }, "Current password", "pw-current", ImeAction.Next)
                    PasswordField(new, { new = it }, "New password (8+ characters)", "pw-new", ImeAction.Next)
                    PasswordField(confirm, { confirm = it }, "Repeat new password", "pw-confirm", ImeAction.Done)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (new != confirm) {
                        error = "The new passwords do not match."
                        return@TextButton
                    }
                    scope.launch {
                        error = container.auth.changePassword(user, current, new)
                        if (error == null) {
                            changing = false
                            snackbar.showSnackbar("Password changed.")
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } },
        )
    }
}
