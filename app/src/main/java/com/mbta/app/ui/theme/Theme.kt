package com.mbta.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun MbtATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
