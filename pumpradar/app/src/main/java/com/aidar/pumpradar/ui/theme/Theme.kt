package com.aidar.pumpradar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Семантика уровней сигнала (используется в UI).
val ColorNormal = Color(0xFF9AA4B2)
val ColorWatch = Color(0xFF3B82F6)
val ColorEarly = Color(0xFFF59E0B)
val ColorStrong = Color(0xFFF97316)
val ColorExtreme = Color(0xFFEF4444)
val ColorRetest = Color(0xFF8B5CF6)
val ColorExhaustion = Color(0xFF991B1B)
val ColorStale = Color(0xFF6B7280)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF39D98A),
    onPrimary = Color(0xFF06281B),
    secondary = Color(0xFFF0B90B),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF141A21),
    onBackground = Color(0xFFE6EAF0),
    onSurface = Color(0xFFE6EAF0),
    error = ColorExtreme
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00875A),
    secondary = Color(0xFFB08400)
)

@Composable
fun PumpRadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
