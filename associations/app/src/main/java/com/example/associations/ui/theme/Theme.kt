package com.example.associations.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Фирменная палитра.
val Violet = Color(0xFF7C5CFF)
val VioletDeep = Color(0xFF5B3FE0)
val Gold = Color(0xFFFFC94D)
val Mint = Color(0xFF34D399)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color(0xFF1A1033),
    tertiary = Mint,
    background = Color(0xFF12102A),
    surface = Color(0xFF1C1840),
    onBackground = Color(0xFFF2EEFF),
    onSurface = Color(0xFFF2EEFF)
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    secondary = Color(0xFFE6A100),
    tertiary = Color(0xFF0E9E6E),
    background = Color(0xFFEDE9FF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1840),
    onSurface = Color(0xFF1C1840)
)

@Composable
fun AssociationsTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}

/** Градиентный фон-подложка для всех экранов. */
@Composable
fun AppBackground(darkTheme: Boolean, content: @Composable () -> Unit) {
    val brush = if (darkTheme) {
        Brush.verticalGradient(
            listOf(Color(0xFF241B4A), Color(0xFF161232), Color(0xFF0C0A1E))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFF1ECFF), Color(0xFFE6E0FF), Color(0xFFDCD4FF))
        )
    }
    Box(Modifier.fillMaxSize().background(brush)) { content() }
}
