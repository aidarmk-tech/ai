package com.aidar.pumpradar.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.domain.model.MonitoringState
import com.aidar.pumpradar.feature.MonitoringViewModel
import com.aidar.pumpradar.feature.coin.labelRu
import com.aidar.pumpradar.ui.theme.ColorEarly
import com.aidar.pumpradar.ui.theme.ColorExtreme
import com.aidar.pumpradar.ui.theme.ColorNormal
import com.aidar.pumpradar.ui.theme.ColorStrong
import com.aidar.pumpradar.ui.theme.ColorWatch

@Composable
fun ScannerScreen(
    onOpenCoin: (String) -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val signals by vm.liveSignals.collectAsStateWithLifecycle()

    if (signals.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Живой сканер", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state is MonitoringState.Running || state is MonitoringState.Starting)
                    "Слежу за рынком. Кандидаты появятся, как только цена начнёт аномально ускоряться."
                else
                    "Запусти мониторинг на вкладке «Обзор», чтобы начать сканирование рынка.",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(signals, key = { it.symbol }) { s -> SignalCard(s) { onOpenCoin(s.symbol) } }
    }
}

@Composable
private fun SignalCard(s: LiveSignal, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.symbol, fontWeight = FontWeight.Bold)
                    LevelChip(s.level, s.stage)
                }
                Text(labelRu(s.opportunityLabel), color = labelColor(s.opportunityLabel),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "Импульс %d · Риск %d · Достоверн. %d".format(
                    s.score, s.entryRiskScore, s.confidenceScore
                ),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
            )
            Text(
                "1м %s · Покупки %s · Объём Z %s · Спред %s".format(
                    pct(s.return60s),
                    s.takerBuyRatio30s?.let { "%.0f%%".format(it * 100) } ?: "—",
                    s.volumeZ30s?.let { "%.1f".format(it) } ?: "…",
                    s.spreadBps?.let { "%.0f bps".format(it) } ?: "—"
                ),
                style = MaterialTheme.typography.bodySmall
            )
            if (s.reasons.isNotEmpty()) {
                Text(s.reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LevelChip(level: String, stage: String) {
    val color = when (level) {
        "EXTREME" -> ColorExtreme; "STRONG" -> ColorStrong
        "EARLY" -> ColorEarly; "WATCH" -> ColorWatch; else -> ColorNormal
    }
    val label = if (stage == "WARMING") "WARMING" else level
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private fun labelColor(label: String): Color = when (label) {
    "CONFIRMED" -> ColorStrong
    "EARLY_CLEAN" -> ColorEarly
    "STRONG_BUT_RISKY", "TOO_LATE", "EXHAUSTION" -> ColorExtreme
    "DATA_INCOMPLETE", "STALE", "CANCELLED" -> ColorNormal
    else -> ColorWatch
}

private fun pct(v: Double?): String = v?.let { "%+.2f%%".format(it) } ?: "—"
