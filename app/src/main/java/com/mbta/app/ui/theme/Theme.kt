package com.mbta.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mbta.app.model.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF89B4FA),
    onPrimary = Color(0xFF11111B),
    primaryContainer = Color(0xFF313244),
    onPrimaryContainer = Color(0xFFCDD6F4),
    secondary = Color(0xFFCBA6F7),
    onSecondary = Color(0xFF11111B),
    background = Color(0xFF11111B),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFA6ADC8),
    outline = Color(0xFF45475A)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E66F5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0A1D4D),
    secondary = Color(0xFF8839EF),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFF6C6F85),
    outline = Color(0xFFBCC0CC)
)

@Composable
fun MbtATheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
        content = content
    )
}
