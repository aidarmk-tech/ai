package com.example.associations.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF2E7D32)
private val GreenDark = Color(0xFF1B5E20)
private val Amber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = Color.Black,
    background = Color(0xFFF1F8E9),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B1B1B),
    onSurface = Color(0xFF1B1B1B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color.Black,
    secondary = Amber,
    onSecondary = Color.Black,
    background = Color(0xFF0E1A12),
    surface = Color(0xFF16241A),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6)
)

@Composable
fun AssociationsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}

/** Цвет акцента категории — для подписи/рамки карты. */
object CategoryColors {
    val city = Color(0xFF1565C0)
    val furniture = Color(0xFF6D4C41)
    val animal = Color(0xFF2E7D32)
    val food = Color(0xFFC62828)
}
