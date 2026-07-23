package com.aidar.pumpradar.feature.coin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.feature.MonitoringViewModel
import com.aidar.pumpradar.ui.component.Sparkline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinDetailScreen(
    symbol: String,
    onBack: () -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val signals by vm.liveSignals.collectAsStateWithLifecycle()
    val s = signals.firstOrNull { it.symbol == symbol }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(symbol) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (s == null) {
                Text("Нет живых данных по $symbol. Открой из «Сканера» при активном мониторинге.",
                    style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (s.spark.size >= 2) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Цена за последние минуты", style = MaterialTheme.typography.bodySmall)
                        Sparkline(s.spark)
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(labelRu(s.opportunityLabel), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    Text("Стадия: ${s.stage}", style = MaterialTheme.typography.bodySmall)
                    Line("Импульс", "${s.score}/100")
                    Line("Риск входа", "${s.entryRiskScore}/100")
                    Line("Достоверность", "${s.confidenceScore}/100")
                    Line("Ликвидность", "Tier ${s.liquidityTier}")
                    Line("Цена", "%.6g".format(s.price))
                    Line("Рост 1 мин", s.return60s?.let { "%+.2f%%".format(it) } ?: "—")
                    Line("Агрессивные покупки", s.takerBuyRatio30s?.let { "%.0f%%".format(it * 100) } ?: "—")
                    Line("CVD 30с", "%+.0f".format(s.cvd30s))
                    Line("Объём Z (10с)", s.volumeZ30s?.let { "%.1f".format(it) } ?: "прогрев…")
                    Line("Спред", s.spreadBps?.let { "%.0f bps".format(it) } ?: "—")
                    Line("OBI top-10", s.obi10?.let { "%+.2f".format(it) } ?: "—")
                    Line("Проскальзывание 10 USDT", s.slippagePercent?.let { "%.2f%%".format(it) } ?: "—")
                }
            }
            if (s.liquidityTier == "D") {
                Text("⚠ Tier D — низкая ликвидность, ВЫСОКИЙ РИСК МАНИПУЛЯЦИИ. " +
                    "Такую монету легко накачать одним кошельком.",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            if (s.reasons.isNotEmpty()) InfoCard("Причины", s.reasons)
            if (s.risks.isNotEmpty()) InfoCard("Риски", s.risks)
            Text("Это рыночная аномалия, а не рекомендация купить.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Человекочитаемая метка возможности (ТЗ 0A.12). */
internal fun labelRu(label: String): String = when (label) {
    "CONFIRMED" -> "ПОДТВЕРЖДЁН"
    "EARLY_CLEAN" -> "РАННИЙ ЧИСТЫЙ"
    "STRONG_BUT_RISKY" -> "СИЛЬНЫЙ, НО РИСКОВЫЙ"
    "TOO_LATE" -> "ПОЗДНО — НЕ ГНАТЬСЯ ЗА ЦЕНОЙ"
    "DATA_INCOMPLETE" -> "ДАННЫЕ НЕПОЛНЫЕ"
    "RETEST" -> "РЕТЕСТ"
    "EXHAUSTION" -> "ИСТОЩЕНИЕ"
    "CANCELLED" -> "ОТМЕНЁН"
    "STALE" -> "УСТАРЕЛ"
    else -> "НАБЛЮДЕНИЕ"
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoCard(title: String, items: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
