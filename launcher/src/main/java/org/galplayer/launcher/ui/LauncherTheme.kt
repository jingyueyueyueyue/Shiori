package org.galplayer.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.galplayer.launcher.model.ThemeMode

val BrandBlue = Color(0xFF2589FF)
val BrandBlueDark = Color(0xFF8AC7FF)

@Composable
fun LauncherTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = BrandBlueDark,
            background = Color(0xFF10141C),
            surface = Color(0xFF1B2230),
            surfaceVariant = Color(0xFF252F40),
            onSurface = Color(0xFFF0F4FA),
        ) else lightColorScheme(
            primary = BrandBlue,
            background = Color(0xFFF5F6FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEDF0FA),
            secondary = Color(0xFF8872D9),
            tertiary = Color(0xFF4DAE9A),
            onSurface = Color(0xFF18202B),
        ),
        content = content,
    )
}
