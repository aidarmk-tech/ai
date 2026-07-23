package com.aidar.pumpradar.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Простой линейный график цены (ТЗ раздел 24.4) на Compose Canvas. */
@Composable
fun Sparkline(
    points: List<Double>,
    modifier: Modifier = Modifier,
    up: Color = Color(0xFF39D98A),
    down: Color = Color(0xFFEF4444)
) {
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (points.size < 2) return@Canvas
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val dx = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * dx
            val y = size.height - ((v - min) / range).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val color = if (points.last() >= points.first()) up else down
        drawPath(path, color, style = Stroke(width = 3f))
        // Точка последней цены.
        val lastX = (points.size - 1) * dx
        val lastY = size.height - ((points.last() - min) / range).toFloat() * size.height
        drawCircle(color, radius = 5f, center = Offset(lastX, lastY))
    }
}
