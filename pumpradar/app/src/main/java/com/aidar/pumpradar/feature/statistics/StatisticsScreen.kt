package com.aidar.pumpradar.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.SignalOutcome
import com.aidar.pumpradar.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    dao: OutcomeDao,
    settings: SettingsRepository
) : ViewModel() {
    val outcomes = dao.completedWithSignal(200).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val calibrating = settings.settings.map { it.calibrationMode }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
}

@Composable
fun StatisticsScreen(vm: StatisticsViewModel = hiltViewModel()) {
    val outcomes by vm.outcomes.collectAsStateWithLifecycle()
    val calibrating by vm.calibrating.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.titleLarge)

        // Калибровка (ТЗ 0A.24): прогресс сбора и доля ложных.
        if (calibrating || outcomes.isNotEmpty()) {
            val target = 200
            val collected = outcomes.size
            val falseRate = if (collected > 0)
                outcomes.count { !successful(it) } * 100.0 / collected else 0.0
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (calibrating) "Калибровка активна" else "Калибровка",
                        fontWeight = FontWeight.Bold)
                    StatRow("Собрано (оценок)", "$collected / $target")
                    if (collected >= 30) {
                        StatRow("Доля ложных", "%.0f%%".format(falseRate))
                    } else {
                        Text("Нужно ≥30 завершённых оценок для устойчивой доли ложных.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (calibrating) {
                        Text("Идёт сбор без системных уведомлений. Пороги автоматически не меняются.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (outcomes.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Пока нет завершённых оценок. Радар следит за ценой 15 минут после " +
                        "каждого сигнала — первые цифры появятся примерно через 15 минут " +
                        "работы мониторинга.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        // Общая сводка.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Всего", fontWeight = FontWeight.Bold)
                val s = summarize(outcomes)
                StatRow("Завершённых оценок", s.total.toString())
                StatRow("Достигли +2% раньше −1%", s.successLabel)
                StatRow("Медиана MFE (макс. рост)", s.medianMfe)
                StatRow("Медиана MAE (макс. просадка)", s.medianMae)
            }
        }

        // Разбивка по уровням.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Точность по уровням", fontWeight = FontWeight.Bold)
                Text(
                    "Доля сигналов, где рост +2% случился раньше просадки −1%.",
                    style = MaterialTheme.typography.bodySmall
                )
                val order = listOf("EXTREME", "STRONG", "EARLY", "WATCH")
                val byLevel = outcomes.groupBy { it.level }
                val present = order.filter { byLevel[it]?.isNotEmpty() == true }
                if (present.isEmpty()) {
                    Text("—", style = MaterialTheme.typography.bodyMedium)
                } else {
                    present.forEach { lvl ->
                        val s = summarize(byLevel.getValue(lvl))
                        StatRow("$lvl (${s.total})", s.successLabel)
                    }
                }
            }
        }

        // Последние сигналы с исходом.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Последние сигналы", fontWeight = FontWeight.Bold)
                outcomes.take(20).forEach { o ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${o.symbol} · ${o.level}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "MFE %s · MAE %s".format(pct(o.mfePercent), pct(o.maePercent)),
                            color = if (successful(o)) Color(0xFF39D98A) else Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(
            "Это не доходность: сделки не исполнялись. Показатели отражают только " +
                "поведение цены после сигнала.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private class Summary(
    val total: Int,
    val successLabel: String,
    val medianMfe: String,
    val medianMae: String
)

private fun summarize(items: List<SignalOutcome>): Summary {
    val total = items.size
    val success = items.count { successful(it) }
    val rate = if (total > 0) success * 100.0 / total else 0.0
    return Summary(
        total = total,
        successLabel = "%d (%.0f%%)".format(success, rate),
        medianMfe = median(items.mapNotNull { it.mfePercent }),
        medianMae = median(items.mapNotNull { it.maePercent })
    )
}

private fun successful(o: SignalOutcome): Boolean {
    val mfe = o.mfePercent ?: return false
    val mae = o.maePercent ?: return false
    return mfe >= 2.0 && mae > -1.0
}

private fun pct(v: Double?): String = v?.let { "%+.2f%%".format(it) } ?: "—"

private fun median(v: List<Double>): String {
    if (v.isEmpty()) return "—"
    val s = v.sorted()
    val m = if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2 else s[s.size / 2]
    return "%+.2f%%".format(m)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
