package com.safestitch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
                // Exposes Compose test tags as view resource IDs, so the on-device smoke test
                // (scripts/device-smoke-test.sh) can drive the real release build.
                SafeStitchNavHost(
                    container = container,
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                )
            }
        }
    }
}
