package com.aidar.pumpradar.feature.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.domain.model.MonitoringState
import com.aidar.pumpradar.feature.MonitoringViewModel

@Composable
fun ScannerScreen(
    onOpenCoin: (String) -> Unit,
    vm: MonitoringViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Живой сканер", style = MaterialTheme.typography.titleLarge)
        Text(
            if (state is MonitoringState.Running)
                "Ожидание первых кандидатов. Как только появится аномальное ускорение цены, монеты появятся здесь."
            else
                "Запусти мониторинг на вкладке «Обзор», чтобы начать сканирование рынка.",
            Modifier.padding(top = 12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
