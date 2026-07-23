package com.aidar.pumpradar.service

import com.aidar.pumpradar.data.local.AppEventDao
import com.aidar.pumpradar.data.local.AppEventEntity
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.local.SignalEntity
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.data.remote.BinanceRest
import com.aidar.pumpradar.data.remote.CandidateStream
import com.aidar.pumpradar.data.remote.MarketStream
import com.aidar.pumpradar.domain.analyzer.CandidateAnalyzer
import com.aidar.pumpradar.domain.analyzer.MarketScanner
import com.aidar.pumpradar.domain.analyzer.PumpScoreCalculator
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.domain.model.SignalLevel
import com.aidar.pumpradar.notification.SignalNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Живой движок (Этапы 2–3): universe → miniTicker → сканер (уровень 1);
 * лучшие кандидаты → aggTrade/bookTicker → анализатор потока (уровень 2) →
 * Pump Score → живые сигналы, персист и уведомления.
 */
@Singleton
class MonitoringEngine @Inject constructor(
    private val rest: BinanceRest,
    private val marketStream: MarketStream,
    private val candidateStream: CandidateStream,
    private val scanner: MarketScanner,
    private val analyzer: CandidateAnalyzer,
    private val scoreCalc: PumpScoreCalculator,
    private val controller: MonitoringController,
    private val settings: SettingsRepository,
    private val signalDao: SignalDao,
    private val appEventDao: AppEventDao,
    private val notifier: SignalNotificationManager,
    private val json: Json
) {
    // Антиспам-персист: символ → (уровень, время последней записи).
    private val emitted = HashMap<String, Pair<Int, Long>>()

    fun start(scope: CoroutineScope) {
        controller.onStarting()
        notifier.reset()
        emitted.clear()
        scope.launch {
            try {
                val universe = rest.usdtUniverse(BinanceRest.DEFAULT_EXCLUDED)
                scanner.setUniverse(universe)
                controller.updateStats { it.copy(usdtSymbols = universe.size) }
                logEvent("INFO", "engine", "Загружено пар: ${universe.size}")
            } catch (e: Exception) {
                Timber.e(e, "universe load failed")
                controller.setDegraded("Не удалось загрузить список пар")
            }

            marketStream.start(
                scope = scope,
                onTickers = { tickers ->
                    scanner.onTicks(tickers)
                    controller.updateStats {
                        it.copy(marketWsConnected = true, lastMessageAt = System.currentTimeMillis())
                    }
                },
                onConnected = { c -> controller.updateStats { it.copy(marketWsConnected = c) } },
                onReconnect = { controller.updateStats { it.copy(reconnects = it.reconnects + 1) } }
            )
            candidateStream.start(
                scope = scope,
                onAggTrade = { analyzer.onAggTrade(it) },
                onBookTicker = { analyzer.onBookTicker(it) },
                onConnected = { c -> controller.updateStats { it.copy(candidateWsConnected = c) } }
            )
            controller.onStarted()

            while (isActive) {
                if (!controller.paused.value) {
                    try {
                        tick()
                    } catch (e: Exception) {
                        Timber.w(e, "engine tick failed")
                    }
                }
                delay(1000)
            }
        }
    }

    private suspend fun tick() {
        val cfg = settings.settings.first()
        val candidates = scanner.computeCandidates(cfg.minimum24hQuoteVolume, MAX_CANDIDATES)
        controller.setCandidates(candidates)

        // Углублённо анализируем лучшие DEEP_CANDIDATES по PreScore.
        val deep = candidates.take(DEEP_CANDIDATES)
        val deepSymbols = deep.map { it.symbol }
        candidateStream.setSymbols(deepSymbols)
        analyzer.retain(deepSymbols.toSet())

        val minLevel = runCatching { SignalLevel.valueOf(cfg.minimumNotificationLevel) }
            .getOrDefault(SignalLevel.STRONG)

        val signals = ArrayList<LiveSignal>(deep.size)
        for (c in deep) {
            val metrics = analyzer.metrics(c.symbol)
            val res = scoreCalc.score(c, metrics)
            val live = LiveSignal(
                symbol = c.symbol,
                price = c.price,
                score = res.score,
                level = res.level.name,
                stage = stageOf(res.level, metrics?.ready == true),
                return60s = c.return60s,
                takerBuyRatio30s = metrics?.takerBuyRatio30s,
                volumeZ30s = metrics?.volumeZ30s,
                cvd30s = metrics?.cvd30s ?: 0.0,
                spreadBps = metrics?.spreadBps,
                reasons = res.reasons,
                risks = res.risks
            )
            signals.add(live)
            maybeEmit(c, live, minLevel)
        }
        signals.sortByDescending { it.score }
        controller.setLiveSignals(signals)
        controller.updateStats { it.copy(candidates = candidates.size) }
    }

    private suspend fun maybeEmit(c: Candidate, live: LiveSignal, minLevel: SignalLevel) {
        val lvl = SignalLevel.valueOf(live.level)
        if (lvl.ordinal < SignalLevel.EARLY.ordinal) return
        val now = System.currentTimeMillis()
        val prev = emitted[c.symbol]
        val cooldownMs = 15 * 60_000L
        val shouldPersist = prev == null || lvl.ordinal > prev.first || now - prev.second >= cooldownMs
        if (!shouldPersist) return
        emitted[c.symbol] = lvl.ordinal to now

        persistSignal(c, live)
        if (lvl.ordinal >= minLevel.ordinal) {
            val cfg = settings.settings.first()
            notifier.maybeNotify(live, cfg.symbolCooldownMinutes)
        }
    }

    private suspend fun persistSignal(c: Candidate, live: LiveSignal) {
        val entity = SignalEntity(
            id = UUID.randomUUID().toString(),
            symbol = c.symbol,
            createdAt = System.currentTimeMillis(),
            level = live.level,
            stage = live.stage,
            score = live.score,
            referencePrice = c.price,
            return15s = c.return15s,
            return60s = c.return60s,
            return5m = c.return5m,
            quoteVolume30s = null,
            volumeZ30s = live.volumeZ30s,
            tradeCount30s = null,
            takerBuyRatio30s = live.takerBuyRatio30s,
            cvd30s = live.cvd30s,
            spreadBps = live.spreadBps,
            obi10 = null,
            slippagePercent = null,
            relativeStrengthVsBtc = c.relativeStrengthVsBtc,
            reasonsJson = json.encodeToString(live.reasons),
            risksJson = json.encodeToString(live.risks),
            dataQualityJson = "{}"
        )
        runCatching { signalDao.insert(entity) }
    }

    private suspend fun logEvent(sev: String, sub: String, msg: String) {
        runCatching {
            appEventDao.insert(AppEventEntity(timestamp = System.currentTimeMillis(),
                severity = sev, subsystem = sub, message = msg))
        }
    }

    private fun stageOf(level: SignalLevel, ready: Boolean): String = when {
        !ready && level.ordinal >= SignalLevel.EARLY.ordinal -> "WARMING"
        else -> level.name
    }

    fun stop() {
        marketStream.stop()
        candidateStream.stop()
        scanner.clear()
        analyzer.clear()
        controller.onStopped()
    }

    private companion object {
        const val MAX_CANDIDATES = 20
        const val DEEP_CANDIDATES = 10
    }
}
