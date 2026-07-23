package com.aidar.pumpradar.feature.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.OutcomeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(dao: OutcomeDao) : ViewModel() {
    val outcomes = dao.completed().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
}

@Composable
fun StatisticsScreen(vm: StatisticsViewModel = hiltViewModel()) {
    val outcomes by vm.outcomes.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Статистика", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val completed = outcomes.size
                val success = outcomes.count { successful(it) }
                val rate = if (completed > 0) success * 100.0 / completed else 0.0
                StatRow("Завершённых оценок", completed.toString())
                StatRow("Достигли +2% раньше −1%", "%d (%.0f%%)".format(success, rate))
                StatRow("Медиана MFE", median(outcomes.mapNotNull { it.mfePercent }))
                StatRow("Медиана MAE", median(outcomes.mapNotNull { it.maePercent }))
            }
        }
        Text(
            "Это не доходность: сделки не исполнялись. Показатели отражают только " +
                "поведение цены после сигнала.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun successful(o: OutcomeEntity): Boolean {
    val mfe = o.mfePercent ?: return false
    val mae = o.maePercent ?: return false
    return mfe >= 2.0 && mae > -1.0
}

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
