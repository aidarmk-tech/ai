package com.aidar.pumpradar.service

import com.aidar.pumpradar.data.remote.BinanceRest
import com.aidar.pumpradar.data.remote.MarketStream
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.domain.analyzer.MarketScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Живой движок мониторинга (Этап 2): грузит universe через REST, слушает
 * !miniTicker@arr, кормит MarketScanner и публикует кандидатов + статистику
 * в MonitoringController раз в секунду.
 */
@Singleton
class MonitoringEngine @Inject constructor(
    private val rest: BinanceRest,
    private val stream: MarketStream,
    private val scanner: MarketScanner,
    private val controller: MonitoringController,
    private val settings: SettingsRepository
) {
    fun start(scope: CoroutineScope) {
        controller.onStarting()
        scope.launch {
            try {
                val universe = rest.usdtUniverse(BinanceRest.DEFAULT_EXCLUDED)
                scanner.setUniverse(universe)
                controller.updateStats { it.copy(usdtSymbols = universe.size) }
            } catch (e: Exception) {
                Timber.e(e, "universe load failed")
                controller.setDegraded("Не удалось загрузить список пар")
            }

            stream.start(
                scope = scope,
                onTickers = { tickers ->
                    scanner.onTicks(tickers)
                    controller.updateStats {
                        it.copy(marketWsConnected = true, lastMessageAt = System.currentTimeMillis())
                    }
                },
                onConnected = { connected ->
                    controller.updateStats { it.copy(marketWsConnected = connected) }
                },
                onReconnect = {
                    controller.updateStats { it.copy(reconnects = it.reconnects + 1) }
                }
            )
            controller.onStarted()

            // Периодический расчёт кандидатов (ТЗ раздел 16: раз в секунду).
            while (isActive) {
                if (!controller.paused.value) {
                    val minVol = runCatching { settings.settings.first().minimum24hQuoteVolume }
                        .getOrDefault(1_000_000.0)
                    val candidates = scanner.computeCandidates(minVol, MAX_CANDIDATES)
                    controller.setCandidates(candidates)
                    controller.updateStats { it.copy(candidates = candidates.size) }
                }
                delay(1000)
            }
        }
    }

    fun stop() {
        stream.stop()
        scanner.clear()
        controller.onStopped()
    }

    private companion object {
        const val MAX_CANDIDATES = 20
    }
}
