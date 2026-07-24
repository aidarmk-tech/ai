package com.aidar.pumpradar.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.preferences.AppSettings
import com.aidar.pumpradar.data.preferences.MonitoringProfile
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.data.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository
) : ViewModel() {
    val settings = repo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings()
    )

    fun setTheme(t: ThemeMode) = viewModelScope.launch { repo.setTheme(t) }
    fun setMinLevel(level: String) = viewModelScope.launch { repo.setMinimumNotificationLevel(level) }
    fun setMinVolume(v: Double) = viewModelScope.launch { repo.setMinimum24hQuoteVolume(v) }
    fun setProfile(p: MonitoringProfile) = viewModelScope.launch { repo.setMonitoringProfile(p) }
    fun setDisclaimer(v: Boolean) = viewModelScope.launch { repo.setShowRiskDisclaimer(v) }
    fun setCalibration(v: Boolean) = viewModelScope.launch { repo.setCalibrationMode(v) }
}

@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)

        Group("Интерфейс") {
            Text("Тема", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { t ->
                    FilterChip(
                        selected = s.theme == t,
                        onClick = { vm.setTheme(t) },
                        label = { Text(when (t) {
                            ThemeMode.SYSTEM -> "Системная"; ThemeMode.LIGHT -> "Светлая"; ThemeMode.DARK -> "Тёмная"
                        }) }
                    )
                }
            }
        }

        Group("Уведомления") {
            Text("Минимальный уровень сигнала", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("WATCH", "EARLY", "STRONG", "EXTREME").forEach { lvl ->
                    FilterChip(
                        selected = s.minimumNotificationLevel == lvl,
                        onClick = { vm.setMinLevel(lvl) },
                        label = { Text(lvl) }
                    )
                }
            }
        }

        Group("Профиль риска") {
            Text("Тиры ликвидности в сканировании", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonitoringProfile.entries.forEach { p ->
                    FilterChip(
                        selected = s.monitoringProfile == p,
                        onClick = { vm.setProfile(p) },
                        label = { Text(when (p) {
                            MonitoringProfile.CAUTIOUS -> "Осторожный"
                            MonitoringProfile.BALANCED -> "Сбалансир."
                            MonitoringProfile.EXPLORE -> "Исследоват."
                        }) }
                    )
                }
            }
            Text(when (s.monitoringProfile) {
                MonitoringProfile.CAUTIOUS -> "Только Tier A/B (крупная ликвидность), меньше шума."
                MonitoringProfile.BALANCED -> "Tier A/B/C — режим по умолчанию."
                MonitoringProfile.EXPLORE -> "Все тиры вкл. D — высокий риск манипуляции, STRONG-уведомления Tier D включены."
            }, style = MaterialTheme.typography.bodySmall)
        }

        Group("Фильтры рынка") {
            Text("Мин. 24ч объём (USDT)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(250_000.0, 500_000.0, 1_000_000.0, 5_000_000.0, 10_000_000.0).forEach { v ->
                    FilterChip(
                        selected = s.minimum24hQuoteVolume == v,
                        onClick = { vm.setMinVolume(v) },
                        label = { Text("%.0fk".format(v / 1000)) }
                    )
                }
            }
        }

        Group("Калибровка") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Калибровочный сбор", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Switch(checked = s.calibrationMode, onCheckedChange = { vm.setCalibration(it) })
            }
            Text("Собирает сигналы в историю БЕЗ системных уведомлений. Дай поработать " +
                "несколько дней (цель ~200 сигналов), затем на экране «Статистика» посмотри " +
                "распределение и долю ложных, чтобы на фактах выбрать свои пороги. " +
                "Рекомендуется как первый режим после установки.",
                style = MaterialTheme.typography.bodySmall)
        }

        Group("Риск") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Показывать предупреждение о риске", Modifier.weight(1f))
                Switch(checked = s.showRiskDisclaimer, onCheckedChange = { vm.setDisclaimer(it) })
            }
        }

        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Диагностика")
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
