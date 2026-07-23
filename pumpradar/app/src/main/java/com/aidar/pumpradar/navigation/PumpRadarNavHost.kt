package com.aidar.pumpradar.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aidar.pumpradar.feature.coin.CoinDetailScreen
import com.aidar.pumpradar.feature.dashboard.DashboardScreen
import com.aidar.pumpradar.feature.diagnostics.DiagnosticsScreen
import com.aidar.pumpradar.feature.history.HistoryScreen
import com.aidar.pumpradar.feature.onboarding.OnboardingScreen
import com.aidar.pumpradar.feature.scanner.ScannerScreen
import com.aidar.pumpradar.feature.settings.SettingsScreen
import com.aidar.pumpradar.feature.statistics.StatisticsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Обзор", Icons.Filled.Dashboard),
    Tab(Routes.SCANNER, "Сканер", Icons.Filled.Radar),
    Tab(Routes.HISTORY, "История", Icons.Filled.History),
    Tab(Routes.STATISTICS, "Статистика", Icons.Filled.Assessment),
    Tab(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

@Composable
fun PumpRadarNavHost(onboardingComplete: Boolean) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingComplete) Routes.DASHBOARD else Routes.ONBOARDING,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                })
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenCoin = { symbol -> navController.navigate(Routes.coin(symbol)) }
                )
            }
            composable(Routes.SCANNER) {
                ScannerScreen(onOpenCoin = { symbol -> navController.navigate(Routes.coin(symbol)) })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenCoin = { symbol -> navController.navigate(Routes.coin(symbol)) })
            }
            composable(Routes.STATISTICS) { StatisticsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) })
            }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.COIN) { entry ->
                CoinDetailScreen(
                    symbol = entry.arguments?.getString("symbol") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
