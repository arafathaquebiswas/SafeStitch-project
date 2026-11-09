package com.safestitch.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(val inspectorName: String, val siteName: String)

/** Defaults used to pre-fill new inspections. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("safestitch_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun update(inspectorName: String, siteName: String) {
        prefs.edit {
            putString(KEY_INSPECTOR, inspectorName.trim())
            putString(KEY_SITE, siteName.trim())
        }
        state.value = read()
    }

    private fun read() = AppSettings(
        inspectorName = prefs.getString(KEY_INSPECTOR, null).orEmpty(),
        siteName = prefs.getString(KEY_SITE, null).orEmpty(),
    )

    private companion object {
        const val KEY_INSPECTOR = "inspector_name"
        const val KEY_SITE = "site_name"
    }
}
