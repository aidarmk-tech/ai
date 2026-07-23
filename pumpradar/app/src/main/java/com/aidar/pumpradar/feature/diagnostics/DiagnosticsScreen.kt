package com.aidar.pumpradar.feature.diagnostics

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
import com.aidar.pumpradar.BuildConfig
import com.aidar.pumpradar.feature.MonitoringViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val events by vm.appEvents.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Состояние", fontWeight = FontWeight.Bold)
                    Line("Версия", BuildConfig.VERSION_NAME)
                    Line("Android", android.os.Build.VERSION.RELEASE)
                    Line("Market WS", if (stats.marketWsConnected) "подключён" else "нет")
                    Line("Candidate WS", if (stats.candidateWsConnected) "подключён" else "нет")
                    Line("Depth WS", if (stats.depthWsConnected) "подключён" else "нет")
                    Line("USDT-пар", stats.usdtSymbols.toString())
                    Line("Кандидатов", stats.candidates.toString())
                    Line("Depth-подписок", stats.depthSymbols.toString())
                    Line("Переподключений", stats.reconnects.toString())
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Журнал (последние ${events.size})", fontWeight = FontWeight.Bold)
                    if (events.isEmpty()) {
                        Text("Событий пока нет.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.take(100).forEach { e ->
                            Text("[${e.severity}] ${e.subsystem}: ${e.message}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
