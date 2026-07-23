package com.aidar.pumpradar.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.domain.model.MonitoringState
import com.aidar.pumpradar.feature.MonitoringViewModel

@Composable
fun DashboardScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenCoin: (String) -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val signalsToday by vm.signalsToday.collectAsStateWithLifecycle()
    val strongToday by vm.strongToday.collectAsStateWithLifecycle()

    val statusText = when (state) {
        is MonitoringState.Stopped -> "Остановлен"
        is MonitoringState.Starting -> "Подключение…"
        is MonitoringState.Running -> if (paused) "На паузе" else "Работает"
        is MonitoringState.Degraded -> "Ограниченный режим: ${(state as MonitoringState.Degraded).message}"
        is MonitoringState.Error -> "Ошибка: ${(state as MonitoringState.Error).message}"
    }
    val active = state is MonitoringState.Running || state is MonitoringState.Starting ||
        state is MonitoringState.Degraded

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PumpRadar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Статус: $statusText", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) {
                        Button(onClick = { vm.startMonitoring() }, modifier = Modifier.weight(1f)) {
                            Text("Начать мониторинг")
                        }
                    } else {
                        OutlinedButton(onClick = { vm.togglePause() }, modifier = Modifier.weight(1f)) {
                            Text(if (paused) "Продолжить" else "Пауза")
                        }
                        Button(onClick = { vm.stopMonitoring() }, modifier = Modifier.weight(1f)) {
                            Text("Остановить")
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Обзор", fontWeight = FontWeight.Bold)
                StatRow("USDT-пар", stats.usdtSymbols.toString())
                StatRow("Кандидатов", stats.candidates.toString())
                StatRow("Depth-анализаторов", stats.depthSymbols.toString())
                StatRow("Сигналов сегодня", signalsToday.toString())
                StatRow("STRONG+ сегодня", strongToday.toString())
                StatRow("Market WS", if (stats.marketWsConnected) "подключён" else "нет")
            }
        }

        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Диагностика")
        }

        Card(Modifier.fillMaxWidth()) {
            Text(
                "Живой сканер и Pump Score подключаются при запуске мониторинга. " +
                    "Это рыночные аномалии, а не рекомендация покупать.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
