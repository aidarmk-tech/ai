package com.example.associations.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateValue(): State<T> = collectAsState()

/** Обёртка над detectTapGestures с одиночным и двойным тапом. */
suspend fun PointerInputScope.detectTapGesturesCompat(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    detectTapGestures(
        onDoubleTap = { onDoubleTap() },
        onTap = { onTap() }
    )
}

/** Обёртка над detectDragGestures с понятными колбэками. */
suspend fun PointerInputScope.detectDragGesturesCompat(
    onStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) {
    detectDragGestures(
        onDragStart = { onStart() },
        onDragEnd = { onEnd() },
        onDragCancel = { onCancel() },
        onDrag = { change, dragAmount ->
            change.consume()
            onDrag(dragAmount)
        }
    )
}
