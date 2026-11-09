package com.safestitch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safestitch.app.data.AuthState
import com.safestitch.app.ui.auth.LoginScreen
import com.safestitch.app.ui.auth.SetupAdminScreen
import com.safestitch.app.ui.navigation.SafeStitchNavHost
import com.safestitch.app.ui.theme.SafeStitchTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SafeStitchApp).container
        setContent {
            SafeStitchTheme {
                // Test tags double as view resource IDs so the on-device test (scripts/smoke) can drive the real build.
                val root = Modifier.semantics { testTagsAsResourceId = true }
                val authState by container.auth.state.collectAsStateWithLifecycle()
                when (val state = authState) {
                    AuthState.Loading -> Box(
                        root.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    AuthState.NeedsSetup -> SetupAdminScreen(container.auth, root)
                    AuthState.SignedOut -> LoginScreen(container.auth, root)
                    // A new user gets a fresh navigation stack.
                    is AuthState.SignedIn -> key(state.user.id) { SafeStitchNavHost(container, state.user, root) }
                }
            }
        }
    }
}
