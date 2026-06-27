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

// Палитра «зелёное сукно + золото», как в классическом пасьянсе.
val FeltGreen = Color(0xFF2E9E63)
val FeltDeep = Color(0xFF155E39)
val Gold = Color(0xFFE9B23C)
val Cream = Color(0xFFFBF7EC)
val Brown = Color(0xFF5A4632)

private val DarkColors = darkColorScheme(
    primary = FeltGreen,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color(0xFF3A2D10),
    background = FeltDeep,
    surface = Cream,
    onBackground = Color(0xFFEAF7EE),
    onSurface = Brown
)

private val LightColors = DarkColors

@Composable
fun AssociationsTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}

/** Зелёное сукно с мягким виньетированием — фон всех экранов. */
@Composable
fun AppBackground(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val brush = Brush.verticalGradient(
        listOf(Color(0xFF34A468), Color(0xFF1F8049), Color(0xFF124E2E))
    )
    Box(Modifier.fillMaxSize().background(brush)) { content() }
}
