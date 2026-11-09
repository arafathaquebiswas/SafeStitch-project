package com.safestitch.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.safestitch.app.R
import com.safestitch.app.data.AuthRepository
import kotlinx.coroutines.launch

/** First launch: the first person creates the admin account. */
@Composable
fun SetupAdminScreen(auth: AuthRepository, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthLayout(
        modifier = modifier,
        title = "Set up SafeStitch",
        subtitle = "Create the first admin account. Admins add inspectors, locations, equipment and checklist questions. " +
            "Accounts are stored on this phone.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("setup-name"),
        )
        UsernameField(username, { username = it }, "setup-username")
        PasswordField(password, { password = it }, "Password (at least 8 characters)", "setup-password", ImeAction.Next)
        PasswordField(confirm, { confirm = it }, "Repeat password", "setup-confirm", ImeAction.Done)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setup-error")) }
        Button(
            onClick = {
                if (password != confirm) {
                    error = "The two passwords do not match."
                    return@Button
                }
                busy = true
                scope.launch {
                    error = auth.createFirstAdmin(name, username, password)
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("setup-create"),
        ) { Text(if (busy) "Creating…" else "Create admin account") }
    }
}

@Composable
fun LoginScreen(auth: AuthRepository, modifier: Modifier = Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthLayout(modifier = modifier, title = "Sign in", subtitle = "Use the account your SafeStitch admin created for you.") {
        UsernameField(username, { username = it }, "login-username")
        PasswordField(password, { password = it }, "Password", "login-password", ImeAction.Done)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login-error")) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = auth.signIn(username, password)
                    if (error != null) password = ""
                    busy = false
                }
            },
            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("login-submit"),
        ) { Text(if (busy) "Signing in…" else "Sign in") }
        Text(
            "Forgot your password? An admin can reset it under Admin › Users.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthLayout(modifier: Modifier, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_admin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text("SafeStitch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun UsernameField(value: String, onChange: (String) -> Unit, tag: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(32)) },
        label = { Text("Username") },
        supportingText = { Text("Letters, numbers, dots or dashes; no spaces") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String, imeAction: ImeAction, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(128)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}
