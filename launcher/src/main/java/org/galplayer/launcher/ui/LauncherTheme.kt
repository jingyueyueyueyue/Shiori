package org.galplayer.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.galplayer.launcher.model.ThemeMode
import org.galplayer.launcher.model.ThemePalette

@Composable
fun LauncherTheme(mode: ThemeMode, palette: ThemePalette, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val primary = Color(if (dark) palette.darkPrimary else palette.lightPrimary)
    val secondary = Color(palette.secondary)
    val tertiary = Color(palette.tertiary)
    val background = if (dark) Color(0xFF10141C) else Color(0xFFF5F6FC)
    val surface = if (dark) Color(0xFF1B2230) else Color.White
    val containerBase = if (dark) surface else Color.White
    val containerContent = if (dark) Color.White else Color.Black
    val colorScheme = if (dark) darkColorScheme(
        primary = primary,
        onPrimary = lerp(primary, Color.Black, 0.82f),
        primaryContainer = lerp(primary, containerBase, 0.72f),
        onPrimaryContainer = lerp(primary, containerContent, 0.82f),
        secondary = secondary,
        secondaryContainer = lerp(secondary, containerBase, 0.72f),
        onSecondaryContainer = lerp(secondary, containerContent, 0.82f),
        tertiary = tertiary,
        tertiaryContainer = lerp(tertiary, containerBase, 0.72f),
        onTertiaryContainer = lerp(tertiary, containerContent, 0.82f),
        background = background,
        surface = surface,
        surfaceVariant = Color(0xFF252F40),
        onSurface = Color(0xFFF0F4FA),
    ) else lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = lerp(primary, containerBase, 0.82f),
        onPrimaryContainer = lerp(primary, containerContent, 0.68f),
        secondary = secondary,
        secondaryContainer = lerp(secondary, containerBase, 0.82f),
        onSecondaryContainer = lerp(secondary, containerContent, 0.68f),
        tertiary = tertiary,
        tertiaryContainer = lerp(tertiary, containerBase, 0.82f),
        onTertiaryContainer = lerp(tertiary, containerContent, 0.68f),
        background = background,
        surface = surface,
        surfaceVariant = Color(0xFFEDF0FA),
        onSurface = Color(0xFF18202B),
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}
