package com.aidar.pumpradar.service

import com.aidar.pumpradar.data.local.AppEventDao
import com.aidar.pumpradar.data.local.AppEventEntity
import com.aidar.pumpradar.data.local.OutcomeDao
import com.aidar.pumpradar.data.local.SignalDao
import com.aidar.pumpradar.data.local.SignalEntity
import com.aidar.pumpradar.data.local.SignalTrajectoryDao
import com.aidar.pumpradar.data.preferences.MonitoringProfile
import com.aidar.pumpradar.data.preferences.SettingsRepository
import com.aidar.pumpradar.data.remote.BinanceRest
import com.aidar.pumpradar.data.remote.CandidateStream
import com.aidar.pumpradar.data.remote.MarketStream
import com.aidar.pumpradar.domain.analyzer.CandidateAnalyzer
import com.aidar.pumpradar.data.local.ClusterDao
import com.aidar.pumpradar.data.local.MarketEventClusterEntity
import com.aidar.pumpradar.data.local.TrainingSnapshotDao
import com.aidar.pumpradar.data.local.TrainingSnapshotEntity
import com.aidar.pumpradar.domain.analyzer.FeatureVector
import com.aidar.pumpradar.domain.analyzer.MarketEventClusterer
import com.aidar.pumpradar.domain.analyzer.MarketScanner
import com.aidar.pumpradar.domain.analyzer.MarketWideMoveDetector
import com.aidar.pumpradar.domain.analyzer.OrderBookAnalyzer
import com.aidar.pumpradar.domain.analyzer.DumpContinuationShortDetector
import com.aidar.pumpradar.domain.analyzer.DumpReboundLongDetector
import com.aidar.pumpradar.domain.analyzer.OutcomeTracker
import com.aidar.pumpradar.domain.analyzer.PumpReversalShortDetector
import com.aidar.pumpradar.domain.analyzer.PumpScoreCalculator
import com.aidar.pumpradar.domain.analyzer.RetestDetector
import com.aidar.pumpradar.domain.analyzer.ShadowOutcomeTracker
import com.aidar.pumpradar.domain.analyzer.ShadowStrategyInput
import com.aidar.pumpradar.data.local.ShadowSignalDao
import com.aidar.pumpradar.domain.model.Candidate
import com.aidar.pumpradar.domain.model.CandidateMetrics
import com.aidar.pumpradar.domain.model.LiquidityTier
import com.aidar.pumpradar.domain.model.LiveSignal
import com.aidar.pumpradar.domain.model.OrderBookMetrics
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
    private val retestDetector: RetestDetector,
    private val pumpReversalShort: PumpReversalShortDetector,
    private val dumpContinuationShort: DumpContinuationShortDetector,
    private val dumpReboundLong: DumpReboundLongDetector,
    private val shadowOutcomeTracker: ShadowOutcomeTracker,
    private val shadowSignalDao: ShadowSignalDao,
    private val clusterer: MarketEventClusterer,
    private val clusterDao: ClusterDao,
    private val trainingSnapshotDao: TrainingSnapshotDao,
    private val signalTrajectoryDao: SignalTrajectoryDao,
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

    // Троттлинг сэмплирования датасета (патч §14).
    private var lastNearMissAt = 0L
    private var lastRandomAt = 0L

    fun start(scope: CoroutineScope) {
        controller.onStarting()
        notifier.reset()
        emitted.clear()
        retention.clear()
        synchronized(warmed) { warmed.clear() }
        lastNearMissAt = 0L
        lastRandomAt = 0L
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
        val raw = scanner.computeCandidates(
            cfg.minimum24hQuoteVolume, MAX_CANDIDATES, now, keep = retention.keys.toSet()
        )
        updateRetention(raw, now)
        // Фильтр по тирам ликвидности согласно профилю (ТЗ 0A.9).
        val allowedTiers = allowedTiersFor(cfg.monitoringProfile)
        val candidates = raw.filter { LiquidityTier.of(it.quoteVolume24h) in allowedTiers }
        controller.setCandidates(candidates)

        // Поток сделок (aggTrade/bookTicker) держим по ВСЕМ кандидатам, чтобы
        // история для «Объём Z» и CVD копилась и не стиралась при перестановке
        // топа. Скоринг/UI — по лучшим DEEP_CANDIDATES, стакан (тяжёлый) — по топу.
        // Самостоятельные нисходящие движения — для теневых SHORT/rebound стратегий.
        // Отдельный список, LONG-пайплайн (signals/UI/уведомления) не затрагивает.
        val downMovers = scanner.computeDownCandidates(cfg.minimum24hQuoteVolume, MAX_SHORT_CANDIDATES, now)
        val downSymbols = downMovers.map { it.symbol }

        val deep = candidates.take(DEEP_CANDIDATES)
        // Поток (aggTrade/bookTicker) держим по кандидатам пампа + нисходящим.
        val flowSymbols = (candidates.map { it.symbol } + downSymbols).distinct()
        val depthSymbols = (candidates.map { it.symbol }.take(DEPTH_CANDIDATES) +
            downSymbols.take(DEPTH_SHORT_CANDIDATES)).distinct()
        val streams = buildSet {
            for (s in flowSymbols) {
                val lo = s.lowercase(); add("$lo@aggTrade"); add("$lo@bookTicker")
            }
            for (s in depthSymbols) add("${s.lowercase()}@depth20@100ms")
        }
        candidateStream.setDesiredStreams(streams)
        val flowSet = flowSymbols.toSet()
        analyzer.retain(flowSet)
        orderBook.retain(depthSymbols.toSet())
        retestDetector.retain(candidates.map { it.symbol }.toSet())
        pumpReversalShort.retain(flowSet)
        dumpContinuationShort.retain(flowSet)
        dumpReboundLong.retain(flowSet)
        clusterer.retain(flowSymbols.toSet())
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

        val lastMsg = controller.stats.value.lastMessageAt
        val feedAge = if (lastMsg > 0) now - lastMsg else null
        val marketCtx = scanner.marketContext(now)   // патч §9

        val signals = ArrayList<LiveSignal>(deep.size)
        val evaluated = ArrayList<Triple<Candidate, LiveSignal, CandidateMetrics?>>(deep.size)
        for (c in deep) {
            val metrics = analyzer.metrics(c.symbol)
            val ob = orderBook.metrics(c.symbol, cfg.slippageTestAmountUsdt)
            val marketWideRisk = MarketWideMoveDetector.risk(
                c.return60s ?: 0.0, marketCtx.medianReturn60s, marketCtx.breadthPositive
            )
            val res = scoreCalc.score(c, metrics, ob, feedAge, marketWideRisk)
            // RETEST (патч §5): подтверждение возобновления после отката перекрывает метку.
            val retest = retestDetector.update(
                c.symbol,
                RetestDetector.Input(
                    now = now, price = c.price,
                    cvd = metrics?.cvd30s ?: 0.0, cvdSlope = metrics?.cvdSlope ?: 0.0,
                    takerBuyRatio = metrics?.takerBuyRatio30s,
                    spreadBps = ob?.spreadBps ?: metrics?.spreadBps,
                    slippagePercent = ob?.buySlippagePercent,
                    confidence = res.confidence, entryRisk = res.entryRisk,
                    isImpulse = res.level.ordinal >= SignalLevel.EARLY.ordinal
                )
            )
            val label = if (retest) "RETEST_CONFIRMED" else res.opportunityLabel
            val live = LiveSignal(
                symbol = c.symbol,
                price = c.price,
                score = res.impulse,
                entryRiskScore = res.entryRisk,
                confidenceScore = res.confidence,
                exhaustionRiskScore = res.exhaustionRisk,
                artificialRiskScore = res.artificialRisk,
                marketWideRiskScore = res.marketWideRisk,
                opportunityLabel = label,
                liquidityTier = res.liquidityTier.name,
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
            evaluated.add(Triple(c, live, metrics))
            // Теневые двусторонние стратегии (SHADOW/PAPER): без ордеров и API key.
            runShadowStrategies(
                c.symbol, c.price, metrics, ob,
                isUp = res.level.ordinal >= SignalLevel.EARLY.ordinal,
                isDown = isDownMove(c), now = now
            )
            // LONG_CONTINUATION = подтверждённый ретест (та же точка, что уведомление LONG).
            if (retest) trackShadow("LONG_CONTINUATION", "LONG", c.symbol, c.price, ob, metrics, now)
            maybeEmit(c, live, metrics)
        }

        // Теневые SHORT/rebound по самостоятельным нисходящим движениям (вне deep).
        val deepSyms = deep.mapTo(HashSet()) { it.symbol }
        for (dc in downMovers) {
            if (dc.symbol in deepSyms) continue
            val m = analyzer.metrics(dc.symbol)
            val ob = orderBook.metrics(dc.symbol, cfg.slippageTestAmountUsdt)
            runShadowStrategies(dc.symbol, dc.price, m, ob, isUp = false, isDown = true, now = now)
        }

        signals.sortByDescending { it.score }
        controller.setLiveSignals(signals)
        controller.updateStats { it.copy(candidates = candidates.size) }

        // Сэмплирование не-сработавших окон для датасета (патч §14).
        sampleNonTriggered(evaluated, now)

        // Отслеживание исходов ранее выданных сигналов + секундная траектория bid/ask.
        outcomeTracker.onTick(
            System.currentTimeMillis(), scanner::priceOf, outcomeDao,
            bookOf = { analyzer.bestBidAsk(it) }, trajectoryDao = signalTrajectoryDao
        )
        // Отслеживание исходов теневых двусторонних сигналов (SHADOW/PAPER).
        shadowOutcomeTracker.onTick(
            System.currentTimeMillis(), scanner::priceOf, { analyzer.bestBidAsk(it) }, shadowSignalDao
        )
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

    private suspend fun maybeEmit(c: Candidate, live: LiveSignal, metrics: CandidateMetrics?) {
        val lvl = SignalLevel.valueOf(live.level)
        // В историю/статистику пишем все EARLY+ (даже не-уведомляемые) — патч §3.
        if (lvl.ordinal < SignalLevel.EARLY.ordinal) return
        val now = System.currentTimeMillis()
        val prev = emitted[c.symbol]
        val cooldownMs = 15 * 60_000L
        val shouldPersist = prev == null || lvl.ordinal > prev.first || now - prev.second >= cooldownMs
        if (!shouldPersist) return
        emitted[c.symbol] = lvl.ordinal to now

        val id = UUID.randomUUID().toString()
        // Кластеризация в одно рыночное событие (патч §12).
        val cluster = clusterer.assign(c.symbol, now, live.score, id)
        persistSignal(id, c, live, cluster.id)
        recordSnapshot("TRIGGERED", id, cluster.id, c, live, metrics, now)
        runCatching {
            clusterDao.upsert(
                MarketEventClusterEntity(
                    id = cluster.id, symbol = c.symbol, startedAt = cluster.startedAt,
                    endedAt = null, firstSignalId = cluster.firstSignalId,
                    peakImpulseScore = cluster.peakImpulse, signalCount = cluster.count,
                    state = "ACTIVE"
                )
            )
        }
        outcomeTracker.track(id, c.symbol, c.price)

        // Уведомления только для основных категорий (патч §2/§3): по умолчанию
        // EARLY_CLEAN и RETEST_CONFIRMED. STRONG-метки в уведомления не идут.
        val cfg = settings.settings.first()
        val notifyByLabel = live.opportunityLabel in NOTIFY_LABELS || cfg.notifyAllCategories
        val tierDMuted = live.liquidityTier == "D" &&
            cfg.monitoringProfile != MonitoringProfile.EXPLORE
        if (notifyByLabel && !tierDMuted && !cfg.calibrationMode) {
            notifier.maybeNotify(live, cfg.symbolCooldownMinutes)
        }
    }

    /**
     * Сэмплирование не-сработавших окон (патч §14): NEAR_MISS — «около-пороговые»
     * кандидаты без уведомления; RANDOM_NORMAL — случайные спокойные окна.
     * Троттлинг по времени, чтобы датасет не был перекошен в сторону TRIGGERED.
     */
    private suspend fun sampleNonTriggered(
        evaluated: List<Triple<Candidate, LiveSignal, CandidateMetrics?>>, now: Long
    ) {
        if (evaluated.isEmpty()) return
        if (now - lastNearMissAt >= NEAR_MISS_INTERVAL_MS) {
            val nm = evaluated
                .filter {
                    it.second.opportunityLabel !in NOTIFY_LABELS &&
                        it.second.score in 40..69 && it.second.confidenceScore >= 55
                }
                .maxByOrNull { it.second.score }
            if (nm != null) {
                recordSnapshot("NEAR_MISS", null, null, nm.first, nm.second, nm.third, now)
                lastNearMissAt = now
            }
        }
        if (now - lastRandomAt >= RANDOM_INTERVAL_MS) {
            val r = evaluated.filter { it.second.score < 40 }.randomOrNull()
            if (r != null) {
                recordSnapshot("RANDOM_NORMAL", null, null, r.first, r.second, r.third, now)
                lastRandomAt = now
            }
        }
    }

    /** Снимок признаков для будущего ML (патч §15/§16). Без будущих данных. */
    private suspend fun recordSnapshot(
        type: String, signalId: String?, eventId: String?, c: Candidate, live: LiveSignal,
        metrics: CandidateMetrics?, now: Long
    ) {
        val fv = FeatureVector(
            return15s = c.return15s, return60s = c.return60s, return5m = c.return5m,
            acceleration = c.acceleration, volumeZ30s = live.volumeZ30s,
            takerBuyRatio30s = live.takerBuyRatio30s, cvd30s = live.cvd30s,
            spreadBps = live.spreadBps, obi10 = live.obi10, slippagePercent = live.slippagePercent,
            relativeStrengthVsBtc = c.relativeStrengthVsBtc,
            largestTradeShare = metrics?.largestTradeShare, top3TradeShare = metrics?.top3TradeShare,
            tinyTradeShare = metrics?.tinyTradeShare,
            impulse = live.score, entryRisk = live.entryRiskScore, confidence = live.confidenceScore,
            exhaustionRisk = live.exhaustionRiskScore, artificialRisk = live.artificialRiskScore,
            marketWideRisk = live.marketWideRiskScore
        )
        runCatching {
            trainingSnapshotDao.insert(
                TrainingSnapshotEntity(
                    id = UUID.randomUUID().toString(),
                    signalId = signalId, eventId = eventId, symbol = c.symbol,
                    snapshotTime = now, snapshotType = type,
                    algorithmVersion = ALGO_VERSION, liquidityTier = live.liquidityTier,
                    opportunityLabel = live.opportunityLabel,
                    featureVectorJson = json.encodeToString(FeatureVector.serializer(), fv)
                )
            )
        }
    }

    /**
     * Прогон трёх теневых двусторонних стратегий по символу. SHADOW/PAPER: только
     * разметка исходов, ордера НЕ отправляются, API-ключ не требуется.
     */
    private suspend fun runShadowStrategies(
        symbol: String, price: Double, metrics: CandidateMetrics?, ob: OrderBookMetrics?,
        isUp: Boolean, isDown: Boolean, now: Long
    ) {
        val input = ShadowStrategyInput(
            now = now, price = price,
            cvd = metrics?.cvd30s ?: 0.0, cvdSlope = metrics?.cvdSlope ?: 0.0,
            takerBuyRatio = metrics?.takerBuyRatio30s,
            spreadBps = ob?.spreadBps ?: metrics?.spreadBps,
            slippagePercent = ob?.buySlippagePercent,
            obi10 = ob?.obi10,
            bidNotionalTop10 = ob?.bidNotionalTop10,
            askNotionalTop10 = ob?.askNotionalTop10,
            isUpImpulse = isUp, isDownImpulse = isDown
        )
        if (pumpReversalShort.update(symbol, input))
            trackShadow("PUMP_REVERSAL_SHORT", "SHORT", symbol, price, ob, metrics, now)
        if (dumpContinuationShort.update(symbol, input))
            trackShadow("DUMP_CONTINUATION_SHORT", "SHORT", symbol, price, ob, metrics, now)
        if (dumpReboundLong.update(symbol, input))
            trackShadow("DUMP_REBOUND_LONG", "LONG", symbol, price, ob, metrics, now)
    }

    private suspend fun trackShadow(
        strategy: String, side: String, symbol: String, price: Double,
        ob: OrderBookMetrics?, metrics: CandidateMetrics?, now: Long
    ) {
        shadowOutcomeTracker.track(
            shadowSignalDao, UUID.randomUUID().toString(), strategy, side, symbol,
            price, ob?.spreadBps ?: metrics?.spreadBps, ob?.buySlippagePercent, now
        )
        logEvent("INFO", "shadow", "%s %s @ %.6f".format(strategy, symbol, price))
    }

    /** Самостоятельное нисходящее движение (зеркально [qualifies]). */
    private fun isDownMove(c: Candidate): Boolean =
        (c.return15s != null && c.return15s <= -0.35) ||
            (c.return60s != null && c.return60s <= -0.80) ||
            (c.acceleration != null && c.acceleration <= -0.30)

    /** Разрешённые тиры для профиля (ТЗ 0A.9). */
    private fun allowedTiersFor(profile: MonitoringProfile): Set<LiquidityTier> = when (profile) {
        MonitoringProfile.CAUTIOUS -> setOf(LiquidityTier.A, LiquidityTier.B)
        MonitoringProfile.BALANCED -> setOf(LiquidityTier.A, LiquidityTier.B, LiquidityTier.C)
        MonitoringProfile.EXPLORE -> setOf(LiquidityTier.A, LiquidityTier.B, LiquidityTier.C, LiquidityTier.D)
    }

    private suspend fun persistSignal(id: String, c: Candidate, live: LiveSignal, eventId: String) {
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
            dataQualityJson = "{}",
            eventId = eventId,
            opportunityLabel = live.opportunityLabel,
            entryRiskScore = live.entryRiskScore,
            confidenceScore = live.confidenceScore,
            exhaustionRisk = live.exhaustionRiskScore,
            artificialRisk = live.artificialRiskScore,
            marketWideRisk = live.marketWideRiskScore,
            liquidityTier = live.liquidityTier,
            algorithmVersion = ALGO_VERSION
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
        retestDetector.clear()
        pumpReversalShort.clear()
        dumpContinuationShort.clear()
        dumpReboundLong.clear()
        clusterer.clear()
        outcomeTracker.clear()
        shadowOutcomeTracker.clear()
        retention.clear()
        synchronized(warmed) { warmed.clear() }
        scopeRef = null
        controller.onStopped()
    }

    private companion object {
        // Метки, дающие системное уведомление по умолчанию (патч §3).
        val NOTIFY_LABELS = setOf("EARLY_CLEAN", "RETEST_CONFIRMED")
        const val ALGO_VERSION = "3.0.0"
        const val MAX_CANDIDATES = 20
        const val DEEP_CANDIDATES = 10
        const val DEPTH_CANDIDATES = 8
        const val MAX_SHORT_CANDIDATES = 6     // нисходящие движения для теневых стратегий
        const val DEPTH_SHORT_CANDIDATES = 4   // стакан по топ-нисходящим (OBI/bid depth)
        const val MIN_LIFETIME_MS = 60_000L   // ТЗ 17.3: минимальное время жизни кандидата
        const val EXIT_GRACE_MS = 30_000L     // ТЗ 17.3: льготный период после порога
        const val NEAR_MISS_INTERVAL_MS = 45_000L   // патч §14: троттлинг NEAR_MISS
        const val RANDOM_INTERVAL_MS = 30_000L      // патч §14: троттлинг RANDOM_NORMAL
    }
}
