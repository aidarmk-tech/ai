package com.aidar.pumpradar.service

import com.aidar.pumpradar.data.local.AppEventDao
import com.aidar.pumpradar.data.local.AppEventEntity
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.local.SignalEntity
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.data.remote.BinanceRest
import com.aidar.pumpradar.data.remote.CandidateStream
import com.aidar.pumpradar.data.remote.MarketStream
import com.aidar.pumpradar.domain.analyzer.CandidateAnalyzer
import com.aidar.pumpradar.domain.analyzer.MarketScanner
import com.aidar.pumpradar.domain.analyzer.OrderBookAnalyzer
import com.aidar.pumpradar.domain.analyzer.OutcomeTracker
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
    private val orderBook: OrderBookAnalyzer,
    private val scoreCalc: PumpScoreCalculator,
    private val outcomeTracker: OutcomeTracker,
    private val controller: MonitoringController,
    private val settings: SettingsRepository,
    private val signalDao: SignalDao,
    private val outcomeDao: OutcomeDao,
    private val appEventDao: AppEventDao,
    private val notifier: SignalNotificationManager,
    private val json: Json
) {
    // Антиспам-персист: символ → (уровень, время последней записи).
    private val emitted = HashMap<String, Pair<Int, Long>>()

    // Удержание кандидатов (ТЗ 17.3): символ → (firstSeen, lastQualified).
    private val retention = HashMap<String, Pair<Long, Long>>()

    // Warm start (ТЗ 0A.4): символы, для которых уже запущен прогрев объёма.
    private val warmed = HashSet<String>()
    @Volatile private var scopeRef: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        controller.onStarting()
        notifier.reset()
        emitted.clear()
        retention.clear()
        synchronized(warmed) { warmed.clear() }
        scopeRef = scope
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
                onDepth = { orderBook.onDepth(it) },
                onConnected = { c -> controller.updateStats { it.copy(candidateWsConnected = c, depthWsConnected = c) } }
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
        val now = System.currentTimeMillis()
        val candidates = scanner.computeCandidates(
            cfg.minimum24hQuoteVolume, MAX_CANDIDATES, now, keep = retention.keys.toSet()
        )
        updateRetention(candidates, now)
        controller.setCandidates(candidates)

        // Поток сделок (aggTrade/bookTicker) держим по ВСЕМ кандидатам, чтобы
        // история для «Объём Z» и CVD копилась и не стиралась при перестановке
        // топа. Скоринг/UI — по лучшим DEEP_CANDIDATES, стакан (тяжёлый) — по топу.
        val flowSymbols = candidates.map { it.symbol }
        val deep = candidates.take(DEEP_CANDIDATES)
        val depthSymbols = flowSymbols.take(DEPTH_CANDIDATES)
        val streams = buildSet {
            for (s in flowSymbols) {
                val lo = s.lowercase(); add("$lo@aggTrade"); add("$lo@bookTicker")
            }
            for (s in depthSymbols) add("${s.lowercase()}@depth20@100ms")
        }
        candidateStream.setDesiredStreams(streams)
        analyzer.retain(flowSymbols.toSet())
        orderBook.retain(depthSymbols.toSet())
        controller.updateStats { it.copy(depthSymbols = depthSymbols.size) }

        // Warm start (ТЗ 0A.4): для ещё не прогретых кандидатов подгружаем базу
        // объёма из 1s-клайнов параллельно, не блокируя tick. `warmed` — набор
        // «в процессе»: снимается по завершении, поэтому rate-limited попытка
        // повторяется на следующем тике (а не блокируется навсегда).
        for (sym in flowSymbols) {
            if (analyzer.isSeeded(sym)) continue
            val started = synchronized(warmed) { warmed.add(sym) }
            if (started) scopeRef?.launch {
                try {
                    warmStart(sym)
                } finally {
                    synchronized(warmed) { warmed.remove(sym) }
                }
            }
        }

        val minLevel = runCatching { SignalLevel.valueOf(cfg.minimumNotificationLevel) }
            .getOrDefault(SignalLevel.STRONG)

        val lastMsg = controller.stats.value.lastMessageAt
        val feedAge = if (lastMsg > 0) now - lastMsg else null

        val signals = ArrayList<LiveSignal>(deep.size)
        for (c in deep) {
            val metrics = analyzer.metrics(c.symbol)
            val ob = orderBook.metrics(c.symbol, cfg.slippageTestAmountUsdt)
            val res = scoreCalc.score(c, metrics, ob, feedAge)
            val live = LiveSignal(
                symbol = c.symbol,
                price = c.price,
                score = res.impulse,
                entryRiskScore = res.entryRisk,
                confidenceScore = res.confidence,
                opportunityLabel = res.opportunityLabel,
                level = res.level.name,
                stage = stageOf(res.level, metrics?.ready == true),
                return60s = c.return60s,
                takerBuyRatio30s = metrics?.takerBuyRatio30s,
                volumeZ30s = metrics?.volumeZ30s,
                cvd30s = metrics?.cvd30s ?: 0.0,
                spreadBps = ob?.spreadBps ?: metrics?.spreadBps,
                obi10 = ob?.obi10,
                slippagePercent = ob?.buySlippagePercent,
                reasons = res.reasons,
                risks = res.risks,
                spark = downsample(scanner.recentPrices(c.symbol, 120), 40)
            )
            signals.add(live)
            maybeEmit(c, live, minLevel)
        }
        signals.sortByDescending { it.score }
        controller.setLiveSignals(signals)
        controller.updateStats { it.copy(candidates = candidates.size) }

        // Отслеживание исходов ранее выданных сигналов.
        outcomeTracker.onTick(System.currentTimeMillis(), scanner::priceOf, outcomeDao)
    }

    /**
     * Удержание кандидатов (ТЗ 17.3). Кандидат остаётся в списке минимум
     * [MIN_LIFETIME_MS] и ещё [EXIT_GRACE_MS] после того, как в последний раз
     * прошёл порог — чтобы не «мигал» и чтобы глубокий анализ успел прогреться.
     */
    private fun updateRetention(candidates: List<Candidate>, now: Long) {
        val present = candidates.mapTo(HashSet()) { it.symbol }
        for (c in candidates) {
            if (qualifies(c)) {
                val firstSeen = retention[c.symbol]?.first ?: now
                retention[c.symbol] = firstSeen to now
            }
        }
        val it = retention.entries.iterator()
        while (it.hasNext()) {
            val (sym, times) = it.next()
            val (firstSeen, lastQualified) = times
            val droppedOut = sym !in present            // упал по объёму/цене
            val expired = now - firstSeen >= MIN_LIFETIME_MS &&
                now - lastQualified >= EXIT_GRACE_MS
            if (droppedOut || expired) it.remove()
        }
    }

    /** Прошёл ли кандидат порог первого уровня прямо сейчас. */
    private fun qualifies(c: Candidate): Boolean =
        (c.return15s != null && c.return15s >= 0.35) ||
            (c.return60s != null && c.return60s >= 0.80) ||
            (c.acceleration != null && c.acceleration >= 0.30)

    /** Прогрев базы объёма кандидата из 1s-клайнов (ТЗ 0A.4). */
    private suspend fun warmStart(symbol: String) {
        if (analyzer.isSeeded(symbol)) return
        val kl = rest.klines1sQuoteVolume(symbol, 600)
        if (kl.isNotEmpty()) {
            analyzer.seedVolume(symbol, kl)
            logEvent("INFO", "warmstart", "Прогрев $symbol: ${kl.size} клайнов")
        }
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

        val id = UUID.randomUUID().toString()
        persistSignal(id, c, live)
        outcomeTracker.track(id, c.symbol, c.price)
        if (lvl.ordinal >= minLevel.ordinal) {
            val cfg = settings.settings.first()
            notifier.maybeNotify(live, cfg.symbolCooldownMinutes)
        }
    }

    private suspend fun persistSignal(id: String, c: Candidate, live: LiveSignal) {
        val entity = SignalEntity(
            id = id,
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
            obi10 = live.obi10,
            slippagePercent = live.slippagePercent,
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

    /** Равномерно прореживает ряд до не более [target] точек. */
    private fun downsample(values: List<Double>, target: Int): List<Double> {
        if (values.size <= target) return values
        val step = values.size.toDouble() / target
        return (0 until target).map { values[(it * step).toInt()] }
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
        orderBook.clear()
        outcomeTracker.clear()
        retention.clear()
        synchronized(warmed) { warmed.clear() }
        scopeRef = null
        controller.onStopped()
    }

    private companion object {
        const val MAX_CANDIDATES = 20
        const val DEEP_CANDIDATES = 10
        const val DEPTH_CANDIDATES = 8
        const val MIN_LIFETIME_MS = 60_000L   // ТЗ 17.3: минимальное время жизни кандидата
        const val EXIT_GRACE_MS = 30_000L     // ТЗ 17.3: льготный период после порога
    }
}
