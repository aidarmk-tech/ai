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
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))

        Section(
            "Цель",
            "Собрать все категории на основаниях по возрастанию ассоциативного " +
                "ранга (1→M). Как только основание собрано полностью — оно «уходит " +
                "с поля», а слот освобождается. Уровень пройден, когда собраны все " +
                "категории."
        )
        Section(
            "Ранг = ассоциация",
            "Внутри категории у карты есть порядок: города — по населению, мебель/" +
                "животные/постройки — по размеру, еда — по «тяжести», транспорт — по " +
                "скорости, время — по длительности. Ранг показан цифрой в углу карты."
        )
        Section(
            "Уровни",
            "С каждой победой сложность растёт: добавляются новые категории " +
                "(Транспорт, Космос, Погода, Время…) и удлиняются ассоциативные " +
                "цепочки — вплоть до ~100 карт за партию. Текущий уровень виден в " +
                "меню и в шапке игры."
        )
        Section(
            "Ходы",
            "• На основание — карту своей категории, ранг которой на 1 больше верхней " +
                "(старт с ранга 1).\n" +
                "• На колонку — карту с рангом на 1 меньше верхней и ДРУГОЙ категории " +
                "(категории чередуются).\n" +
                "• На пустую колонку — любую карту.\n" +
                "• Серию подходящих карт можно переносить целиком."
        )
        Section(
            "Управление",
            "• Перетащите карту пальцем на нужную стопку.\n" +
                "• Или тапните карту, затем тапните стопку-назначение (подсветятся " +
                "доступные ходы).\n" +
                "• Двойной тап — авто-отправка карты на основание.\n" +
                "• Тап по колоде — добор в сброс; когда колода пуста — переворот сброса."
        )
        Section(
            "Подсказки",
            "Кнопка «Отменить» откатывает ходы. «Новая» раздаёт партию заново. " +
                "Прогресс и время сохраняются автоматически при выходе."
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
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
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
