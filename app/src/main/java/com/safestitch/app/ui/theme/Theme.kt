package com.safestitch.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E69),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F0E8),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF051F1D),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFB3261E),
    background = Color(0xFFF6FAF9),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6FAF9),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD4CC),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFFA6F0E8),
    secondary = Color(0xFFB1CCC8),
    onSecondary = Color(0xFF1C3532),
    secondaryContainer = Color(0xFF334B48),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFFFB960),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFF2B8B5),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
)

/** Colours for PASS / FAIL / N/A / draft states, tuned for light and dark mode. */
@Immutable
data class StatusColors(val pass: Color, val fail: Color, val neutral: Color, val draft: Color)

private val LightStatus = StatusColors(
    pass = Color(0xFF1B7F3B),
    fail = Color(0xFFB3261E),
    neutral = Color(0xFF5F6B69),
    draft = Color(0xFF8A5100),
)
private val DarkStatus = StatusColors(
    pass = Color(0xFF7DDA95),
    fail = Color(0xFFF2B8B5),
    neutral = Color(0xFFBEC9C6),
    draft = Color(0xFFFFB960),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun SafeStitchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
