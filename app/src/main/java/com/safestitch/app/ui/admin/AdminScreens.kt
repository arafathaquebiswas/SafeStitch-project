package com.safestitch.app.ui.admin

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.AppContainer
import com.safestitch.app.BuildConfig
import com.safestitch.app.R
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.domain.UserRole
import com.safestitch.app.sync.SyncConfig
import com.safestitch.app.sync.SyncPhase
import com.safestitch.app.ui.auth.PasswordField
import com.safestitch.app.ui.auth.UsernameField
import com.safestitch.app.ui.components.BackButton
import com.safestitch.app.ui.components.formatDateTime
import com.safestitch.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    onBack: () -> Unit,
    onUsers: () -> Unit,
    onLocations: () -> Unit,
    onChecklists: () -> Unit,
    onSync: () -> Unit,
    onEquipment: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Admin tools") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AdminEntry("Users", "Add inspectors and admins, reset passwords, deactivate accounts.", R.drawable.ic_person, "admin-users", onUsers)
            AdminEntry("Locations", "Factory › building › floor › area.", R.drawable.ic_location, "admin-locations", onLocations)
            AdminEntry("Equipment", "Add, edit and print QR labels for equipment.", R.drawable.ic_qr_scan, "admin-equipment", onEquipment)
            AdminEntry("Checklist questions", "Add, edit, reorder or switch off questions per equipment type.", R.drawable.ic_edit, "admin-checklists", onChecklists)
            AdminEntry("Sync", "Optional: upload inspections and corrective actions to your server when online.", R.drawable.ic_sync, "admin-sync", onSync)
        }
    }
}

@Composable
private fun AdminEntry(title: String, subtitle: String, @DrawableRes icon: Int, tag: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

// ---------------------------------------------------------------------------------------------
//  Users
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun UsersScreen(container: AppContainer, admin: UserEntity, onBack: () -> Unit) {
    val users by remember { container.auth.observeUsers() }.collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<UserEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Users") }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }, modifier = Modifier.testTag("add-user")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add user")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("users-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(users, key = { it.id }) { user ->
                Card(Modifier.fillMaxWidth().testTag("user-${user.username}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(user.displayName + if (user.id == admin.id) " (you)" else "", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "@${user.username} · ${user.role.label} · ${if (user.active) "Active" else "Deactivated"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (user.active) MaterialTheme.colorScheme.onSurfaceVariant else LocalStatusColors.current.fail,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { resetting = user }) { Text("Reset password") }
                            if (user.id != admin.id) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val error = container.auth.setActive(admin, user.id, !user.active)
                                        snackbar.showSnackbar(error ?: if (user.active) "${user.displayName} deactivated." else "${user.displayName} activated.")
                                    }
                                }) { Text(if (user.active) "Deactivate" else "Activate") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        var username by rememberSaveable { mutableStateOf("") }
        var role by rememberSaveable { mutableStateOf(UserRole.INSPECTOR) }
        var password by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { if (!busy) adding = false },
            title = { Text("Add user") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("user-name"),
                    )
                    UsernameField(username, { username = it }, "user-username")
                    UserRole.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(role == option, onClick = { role = option }, role = Role.RadioButton).testTag("user-role-${option.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = role == option, onClick = null)
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    PasswordField(password, { password = it }, "Password (8+ characters)", "user-password", ImeAction.Next)
                    PasswordField(confirm, { confirm = it }, "Repeat password", "user-confirm", ImeAction.Done)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("user-error")) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (password != confirm) {
                            error = "The two passwords do not match."
                            return@TextButton
                        }
                        busy = true
                        scope.launch {
                            error = container.auth.createUser(admin, name, username, role, password)
                            busy = false
                            if (error == null) {
                                adding = false
                                snackbar.showSnackbar("Added ${name.trim()} as ${role.label.lowercase()}.")
                            }
                        }
                    },
                    modifier = Modifier.testTag("user-save"),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text("Cancel") } },
        )
    }

    resetting?.let { target ->
        var password by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { resetting = null },
            title = { Text("New password for ${target.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(password, { password = it }, "New password (8+ characters)", "reset-password", ImeAction.Done)
                    Text("Tell the person their new password in person; they can change it under Settings.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        error = container.auth.resetPassword(admin, target.id, password)
                        if (error == null) {
                            resetting = null
                            snackbar.showSnackbar("Password reset for ${target.displayName}.")
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
//  Sync
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit) {
    val sync = container.syncManager
    val config by sync.config.collectAsStateWithLifecycle()
    val status by sync.status.collectAsStateWithLifecycle()
    val pending by remember { sync.pendingCount }.collectAsStateWithLifecycle(0)
    val pendingPhotos by remember { sync.pendingPhotoCount }.collectAsStateWithLifecycle(0)
    val conflicts by remember { sync.conflicts }.collectAsStateWithLifecycle(emptyList())
    val phase by remember { sync.phase }.collectAsStateWithLifecycle(SyncPhase.OFF)
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }
    var url by rememberSaveable { mutableStateOf(config.serverUrl) }
    var apiKey by rememberSaveable { mutableStateOf(config.apiKey) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmResend by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(config) { enabled = config.enabled }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("sync-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Off by default. SafeStitch always works offline. When sync is on, submitted inspections, corrective-action " +
                    "changes and their photos are uploaded to your server. While the phone is offline they wait in a queue " +
                    "that survives restarts, and they are sent automatically when a connection returns. Account names, " +
                    "passwords and password hashes are never uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sync to server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it }, modifier = Modifier.testTag("sync-enabled"))
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.take(200) },
                label = { Text("Server address") },
                placeholder = { Text("https://sync.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("sync-url"),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.take(200) },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("sync-key"),
            )
            Text(
                if (BuildConfig.DEBUG) {
                    "Test build: http://10.0.2.2 (emulator → this computer) and http://localhost are allowed for testing. Release builds require https."
                } else {
                    "Only secure https:// addresses are allowed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sync-error")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        error = sync.save(SyncConfig(enabled, url, apiKey))
                        if (error == null) scope.launch { snackbar.showSnackbar(if (enabled) "Sync is on." else "Sync is off.") }
                    },
                    modifier = Modifier.testTag("sync-save"),
                ) { Text("Save") }
                OutlinedButton(onClick = { sync.syncNow() }, enabled = config.enabled, modifier = Modifier.testTag("sync-now")) { Text("Sync now") }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status: ${phase.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("sync-phase"))
                    Text("Waiting to upload: $pending record(s), $pendingPhotos photo(s)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sync-pending"))
                    status.lastSuccessAt?.let { Text("Last successful sync: ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync-last-success")) }
                    status.lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync-summary")) }
                    status.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sync-last-error")) }
                    Text("Device ID: ${sync.deviceId.take(8)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Conflicts: ${conflicts.size}",
                style = MaterialTheme.typography.titleMedium,
                color = if (conflicts.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("sync-conflicts"),
            )
            if (conflicts.isEmpty()) {
                Text("None. The server and this phone agree.", style = MaterialTheme.typography.bodySmall)
            }
            conflicts.forEach { conflict ->
                Card(Modifier.fillMaxWidth().testTag("conflict-${conflict.kind.name}-${conflict.localId}")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conflict.label, style = MaterialTheme.typography.titleSmall)
                        Text(conflict.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { container.repository.resolveConflict(conflict, sendMine = false) } }) { Text("Keep server copy") }
                            TextButton(onClick = { scope.launch { container.repository.resolveConflict(conflict, sendMine = true) } }) { Text("Send mine again") }
                        }
                    }
                }
            }
            TextButton(onClick = { confirmResend = true }, enabled = config.enabled, modifier = Modifier.testTag("sync-resend-all")) {
                Text("Re-upload everything")
            }
        }
    }

    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("Re-upload everything?") },
            text = { Text("All submitted inspections, corrective actions and photos are sent again. The server recognises copies it already has, so nothing is duplicated.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResend = false
                    scope.launch {
                        container.repository.resetSyncState()
                        sync.syncNow()
                        snackbar.showSnackbar("Re-upload queued.")
                    }
                }) { Text("Re-upload") }
            },
            dismissButton = { TextButton(onClick = { confirmResend = false }) { Text("Cancel") } },
        )
    }
}
