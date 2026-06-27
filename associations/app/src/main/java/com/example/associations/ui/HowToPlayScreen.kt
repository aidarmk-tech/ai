package com.example.associations.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "Как играть",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))

        Section(
            "Идея",
            "Каждая ассоциация — это карта-ОСНОВА (★, напр. «Город», «Рыцарь») и её " +
                "предметы (Москва, Лондон… / меч, щит, конь…). Нужно собрать предметы " +
                "на свою основу."
        )
        Section(
            "Основа",
            "Карту-основу можно положить только в пустой слот-фундамент сверху или на " +
                "пустую колонку. Поставил основу в фундамент — он закрепился за этой " +
                "группой."
        )
        Section(
            "Предметы",
            "Предмет кладётся на фундамент своей группы в ЛЮБОМ порядке — Москва или " +
                "Лондон, без разницы. Также предмет можно положить на колонку, где " +
                "сверху карта той же группы, или на пустую колонку."
        )
        Section(
            "Сбор группы",
            "Когда на фундаменте собраны основа и все предметы — группа «уходит с поля» " +
                "(✓), слот освобождается. Счётчик k/6 на фундаменте показывает прогресс."
        )
        Section(
            "Управление",
            "• Тяните карту пальцем на нужный слот.\n" +
                "• Или коснитесь карты, затем места назначения — подсказки подсветятся.\n" +
                "• Двойной тап — авто-отправка на подходящий фундамент.\n" +
                "• Тап по колоде — добор; пустая колода переворачивает сброс."
        )
        Section(
            "Уровни и фишки",
            "Уровень 1 — 4 ассоциации, дальше 5, 6, 7… (до 12). За каждую победу " +
                "начисляются фишки 🪙 — больше за скорость и меньшее число ходов. " +
                "Уровень и фишки сохраняются."
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Назад") }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(
        title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        body,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(16.dp))
}
