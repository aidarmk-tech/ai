package com.aidar.pumpradar.navigation

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SCANNER = "scanner"
    const val HISTORY = "history"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val COIN = "coin/{symbol}"

    fun coin(symbol: String) = "coin/$symbol"
}
