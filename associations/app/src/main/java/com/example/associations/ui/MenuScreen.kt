package com.example.associations.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.associations.ui.theme.Gold

@Composable
fun MenuScreen(
    level: Int,
    coins: Int,
    hasSavedGame: Boolean,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    onHowTo: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🧩", fontSize = 60.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "АССОЦИАЦИИ",
            fontSize = 38.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "собирай группы — открывай уровни",
            fontSize = 13.sp,
            color = Color(0xAAFFFFFF)
        )
        Spacer(Modifier.height(20.dp))

        // Карточки статистики.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatChip("🏆", "Уровень", level.toString())
            StatChip("🪙", "Фишки", coins.toString())
        }
        Spacer(Modifier.height(28.dp))

        if (hasSavedGame) {
            GradientButton("Продолжить", listOf(Color(0xFF34D399), Color(0xFF10B981)), onContinue)
            Spacer(Modifier.height(12.dp))
        }
        GradientButton(
            if (hasSavedGame) "Новая игра" else "Играть",
            listOf(Gold, Color(0xFFCC8A1E)),
            onPlay
        )
        Spacer(Modifier.height(12.dp))
        GhostButton("Как играть", onHowTo)
        Spacer(Modifier.height(12.dp))
        GhostButton("Настройки", onSettings)
    }
}

@Composable
private fun StatChip(icon: String, label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x1AFFFFFF))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 22.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Gold)
        Text(label, fontSize = 11.sp, color = Color(0x99FFFFFF))
    }
}

@Composable
private fun GradientButton(text: String, colors: List<Color>, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(colors))
            .clickable { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x14FFFFFF))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFFE7E0FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
