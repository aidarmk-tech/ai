package com.aidar.pumpradar.feature.history

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.local.SignalEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(dao: SignalDao) : ViewModel() {
    val signals = dao.recent(500).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
}

@Composable
fun HistoryScreen(
    onOpenCoin: (String) -> Unit,
    vm: HistoryViewModel = hiltViewModel()
) {
    val signals by vm.signals.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    if (signals.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("История сигналов", style = MaterialTheme.typography.titleLarge)
            Text(
                "Здесь появятся сохранённые сигналы после запуска мониторинга.",
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
        items(signals, key = { it.id }) { s -> SignalCard(s, fmt.format(Date(s.createdAt))) { onOpenCoin(s.symbol) } }
    }
}

@Composable
private fun SignalCard(s: SignalEntity, time: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.symbol, fontWeight = FontWeight.Bold)
                Text("${s.level} · ${s.score}/100", fontWeight = FontWeight.Bold)
            }
            Text(time, style = MaterialTheme.typography.bodySmall)
            val r1 = s.return60s?.let { "1м %+.2f%%".format(it) } ?: ""
            Text("$r1  цена ${s.referencePrice}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
