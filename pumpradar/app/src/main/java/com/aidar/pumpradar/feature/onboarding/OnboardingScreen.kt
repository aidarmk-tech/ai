package com.aidar.pumpradar.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aidar.pumpradar.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {
    fun complete() = viewModelScope.launch { settings.setOnboardingComplete(true) }
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel()
) {
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не блокирует онбординг */ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("PumpRadar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Сканер аномалий рынка Binance Spot. Приложение анализирует публичные " +
                "данные и предупреждает о резких движениях цены и потоке сделок.",
            style = MaterialTheme.typography.bodyLarge
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Чего приложение НЕ делает", fontWeight = FontWeight.Bold)
                Text("• не торгует и не создаёт ордера;")
                Text("• не запрашивает API-ключи Binance;")
                Text("• не даёт доступа к твоим деньгам;")
                Text("• не гарантирует прибыль.")
            }
        }
        Card {
            Text(
                "PumpRadar обнаруживает рыночные аномалии, но не предсказывает прибыль. " +
                    "Резкий рост часто заканчивается быстрым падением. Не используйте сигнал " +
                    "как единственное основание для сделки и не рискуйте деньгами, потеря " +
                    "которых для вас существенна.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Разрешить уведомления") }
        Button(
            onClick = { vm.complete(); onDone() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Начать") }
        Spacer(Modifier.height(24.dp))
    }
}
