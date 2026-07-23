package com.aidar.pumpradar.feature.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.MonitoringState
import com.aidar.pumpradar.feature.MonitoringViewModel

@Composable
fun ScannerScreen(
    onOpenCoin: (String) -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()

    if (candidates.isEmpty()) {
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
        items(candidates, key = { it.symbol }) { c ->
            CandidateCard(c) { onOpenCoin(c.symbol) }
        }
    }
}

@Composable
private fun CandidateCard(c: Candidate, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(c.symbol, fontWeight = FontWeight.Bold)
                Text("score ${c.preScore.toInt()}", fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("цена %.6g".format(c.price), style = MaterialTheme.typography.bodySmall)
                Text(
                    "15с %s · 1м %s".format(fmt(c.return15s), fmt(c.return60s)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "объём 24ч %.1fM%s".format(
                    c.quoteVolume24h / 1_000_000.0,
                    c.relativeStrengthVsBtc?.let { " · vs BTC %+.2f%%".format(it) } ?: ""
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun fmt(v: Double?): String = v?.let { "%+.2f%%".format(it) } ?: "—"
