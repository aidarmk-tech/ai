package com.example.associations.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MenuScreen(
    hasSavedGame: Boolean,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    onHowTo: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🃏", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ассоциации",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "пасьянс по категориям",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(36.dp))

        if (hasSavedGame) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
            ) { Text("Продолжить") }
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
        ) { Text(if (hasSavedGame) "Новая игра" else "Играть") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onHowTo,
            modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
        ) { Text("Как играть") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp)
        ) { Text("Настройки") }
    }
}
