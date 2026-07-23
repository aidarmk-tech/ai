package com.aidar.pumpradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aidar.pumpradar.data.preferences.ThemeMode
import com.aidar.pumpradar.feature.MonitoringViewModel
import com.aidar.pumpradar.navigation.PumpRadarNavHost
import com.aidar.pumpradar.ui.theme.PumpRadarTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MonitoringViewModel = hiltViewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            PumpRadarTheme(darkTheme = dark) {
                PumpRadarNavHost(onboardingComplete = settings.onboardingComplete)
            }
        }
    }
}
