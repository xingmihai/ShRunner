package com.shrunner.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import com.shrunner.app.data.ThemeMode
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Restrained slate-blue palette with a muted green for success. Neutrals are overridden
// explicitly so the default purple-tinted M3 surface containers never show up.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D8A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7), onPrimaryContainer = Color(0xFF0B2239),
    secondary = Color(0xFF566272), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE3EF), onSecondaryContainer = Color(0xFF131C27),
    tertiary = Color(0xFF2E7D5B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDEBD3), onTertiaryContainer = Color(0xFF002114),
    background = Color(0xFFF8F9FB), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FB), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDEE3EB), onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF72777F), outlineVariant = Color(0xFFC2C7CF),
    surfaceDim = Color(0xFFD8DBE0), surfaceBright = Color(0xFFF8F9FB),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFECEFF3), surfaceContainerHigh = Color(0xFFE6E9EE),
    surfaceContainerHighest = Color(0xFFE0E4E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FC8F0), onPrimary = Color(0xFF0A2A4A),
    primaryContainer = Color(0xFF214A72), onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFBDC7D4), onSecondary = Color(0xFF28313C),
    secondaryContainer = Color(0xFF3E4854), onSecondaryContainer = Color(0xFFDAE3EF),
    tertiary = Color(0xFF8FD4B0), onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF0F5237), onTertiaryContainer = Color(0xFFBDEBD3),
    background = Color(0xFF111418), onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111418), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF42474E), onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C919A), outlineVariant = Color(0xFF42474E),
    surfaceDim = Color(0xFF111418), surfaceBright = Color(0xFF373A3F),
    surfaceContainerLowest = Color(0xFF0C0F13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

@Composable
fun ShRunnerTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
