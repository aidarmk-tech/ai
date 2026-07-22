package com.binancetrader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/** Состояние одной торговой пары: контекст, кулдаун и открытая позиция. */
private class PairState(
    val symbol: String,
    val rules: SymbolRules,
    var lastClosedTime: Long = 0L,
    var lastAtr: Double = 0.0,
    var ctxUp: Boolean = false,
    var cooldownBars: Int = 0,
    // позиция
    var inPosition: Boolean = false,
    var posType: String = "",          // "MOM" | "REV"
    var entryPrice: Double = 0.0,
    var qty: Double = 0.0,
    var spentQuote: Double = 0.0,
    var stopPrice: Double = 0.0,
    var targetPrice: Double = 0.0,     // 0 — цели нет (трейлинг)
    var trailDistance: Double = 0.0,
    var highestPrice: Double = 0.0,
    var barsHeld: Int = 0,
    var breakeven: Boolean = false
)

/**
 * Foreground-сервис: торгует несколькими парами по стратегии «Хамелеон»
 * (см. Strategy.kt). Раз в POLL_MS опрашивает рынок; сигналы входа/выхода
 * считаются по закрытым 15m-свечам, стопы и цели — по последней цене.
 */
class TradingService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "trading"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 25_000L
        private const val AUTO_WATCHLIST = 8
        private const val AUTO_REFRESH_MS = 6 * 60 * 60 * 1000L

        /** Риск на сделку: сколько капитала теряем, если сработает стоп. */
        private const val RISK_PCT = 1.5
        /** Дневной лимит убытка: после него новые входы закрыты до завтра (UTC). */
        private const val DAILY_LOSS_PCT = 3.0
        private const val COOLDOWN_BARS = 8
        private const val MOM_TIME_STOP_BARS = 32
        private const val REV_TIME_STOP_BARS = 16

        fun start(context: Context) {
            val i = Intent(context, TradingService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TradingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    // Дневной риск-контроль (переживает перезапуск через Prefs).
    private var dayKey = ""
    private var dayStartEquity = 0.0
    private var dayRealizedPnl = 0.0
    private var dailyLossHit = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop("Остановлено пользователем")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("Запуск…"))
                if (loopJob?.isActive != true) startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        BotState.setRunning(false)
        super.onDestroy()
    }

    private fun startLoop() {
        Prefs.load(this)
        BotState.setRunning(true)
        log("── Бот запущен (стратегия «Хамелеон») ──")

        loopJob = scope.launch {
            val symbolsSetting = Prefs.getString(this@TradingService, "symbols", "AUTO")
                .uppercase(Locale.US)
            val interval = Prefs.getString(this@TradingService, "interval", "15m")
            val positionPct = Prefs.getString(this@TradingService, "positionPct", "20").toDoubleOrNull() ?: 20.0
            val maxPositions = Prefs.getString(this@TradingService, "maxPositions", "3").toIntOrNull() ?: 3
            val testnet = Prefs.getBoolean(this@TradingService, "testnet", true)

            val client = BinanceClient(Prefs.apiKey, Prefs.apiSecret, testnet)

            val manualSymbols = symbolsSetting.split(',', ';', ' ')
                .map { it.trim() }.filter { it.isNotEmpty() && it != "AUTO" }
            val autoMode = manualSymbols.isEmpty()

            log(
                if (autoMode) "Пары: АВТО (топ-$AUTO_WATCHLIST по обороту, пересмотр каждые 6 ч)"
                else "Пары: ${manualSymbols.joinToString(", ")}"
            )
            log("ТФ входа: $interval, контекст: 1h, риск на сделку: $RISK_PCT% капитала (потолок ${positionPct.toInt()}%), макс. позиций: $maxPositions")
            log(if (testnet) "Режим: ТЕСТНЕТ (виртуальные деньги)" else "⚠ Режим: РЕАЛЬНАЯ ТОРГОВЛЯ")

            var pairs: MutableList<PairState>
            try {
                client.syncTime()
                pairs = buildPairs(client, autoMode, manualSymbols)
            } catch (e: Exception) {
                log("Ошибка инициализации: ${e.message}")
                stopLoop("Ошибка: ${e.message}")
                stopSelf()
                return@launch
            }
            log("Наблюдаю: ${pairs.joinToString(", ") { it.symbol }}")
            loadDayRisk()

            var lastAutoRefresh = System.currentTimeMillis()
            var btcNotBear = true
            var lastBtcCheck = 0L
            var errorStreak = 0

            while (isActive) {
                if (autoMode && System.currentTimeMillis() - lastAutoRefresh > AUTO_REFRESH_MS) {
                    try {
                        pairs = refreshAutoPairs(client, pairs)
                        lastAutoRefresh = System.currentTimeMillis()
                        log("Список пар обновлён: ${pairs.joinToString(", ") { it.symbol }}")
                    } catch (e: Exception) {
                        log("Не удалось обновить список пар: ${e.message}")
                    }
                }

                // Макро-фильтр BTC — раз в 10 минут.
                if (System.currentTimeMillis() - lastBtcCheck > 10 * 60 * 1000L) {
                    try {
                        val btc1h = client.klines("BTCUSDT", "1h", 220)
                            .filter { it.closeTime <= System.currentTimeMillis() }
                        val prev = btcNotBear
                        btcNotBear = Strategy.notBearMarket(btc1h)
                        lastBtcCheck = System.currentTimeMillis()
                        if (prev != btcNotBear) {
                            log(
                                if (btcNotBear) "BTC снова выше SMA200(1h) — входы разрешены"
                                else "⚠ BTC ниже SMA200(1h) — медвежий рынок, новые входы запрещены"
                            )
                        }
                    } catch (_: Exception) {
                    }
                }

                rollDayIfNeeded(client, pairs)

                var cycleFailed = false
                for (p in pairs) {
                    if (!isActive) break
                    try {
                        processPair(client, p, pairs, interval, positionPct, maxPositions, btcNotBear)
                    } catch (e: Exception) {
                        cycleFailed = true
                        log("${p.symbol}: ошибка — ${e.message}")
                        if (e is BinanceException && (e.code == -2014 || e.code == -2015 || e.code == -1022)) {
                            log("Ключи API не подходят — останавливаюсь.")
                            stopLoop("Неверные ключи API")
                            stopSelf()
                            return@launch
                        }
                    }
                }

                if (cycleFailed && ++errorStreak >= 3) {
                    try {
                        client.syncTime()
                    } catch (_: Exception) {
                    }
                    errorStreak = 0
                } else if (!cycleFailed) errorStreak = 0

                updateStatus(pairs, btcNotBear)
                delay(POLL_MS)
            }
        }
    }

    private fun processPair(
        client: BinanceClient,
        p: PairState,
        pairs: List<PairState>,
        interval: String,
        positionPct: Double,
        maxPositions: Int,
        btcNotBear: Boolean
    ) {
        val now = System.currentTimeMillis()
        val closed = client.klines(p.symbol, interval, 120).filter { it.closeTime <= now }
        if (closed.size < Strategy.MIN_CANDLES) return

        val price = client.lastPrice(p.symbol)
        val newCandle = closed.last().closeTime != p.lastClosedTime

        if (newCandle) {
            p.lastClosedTime = closed.last().closeTime
            p.lastAtr = Strategy.atr(closed, Strategy.ATR_PERIOD)
            if (p.cooldownBars > 0) p.cooldownBars--
            // Контекст 1h обновляем на закрытии 15m-свечи (он меняется медленно).
            try {
                val c1h = client.klines(p.symbol, "1h", 150).filter { it.closeTime <= now }
                p.ctxUp = Strategy.contextTrendUp(c1h)
            } catch (_: Exception) {
            }
            if (p.inPosition) p.barsHeld++
        }

        if (!p.inPosition) {
            if (!newCandle) return
            if (p.cooldownBars > 0 || dailyLossHit) return
            if (pairs.count { it.inPosition } >= maxPositions) return
            val plan = Strategy.considerEntry(closed, p.ctxUp, btcNotBear) ?: return
            tryBuy(client, p, pairs, plan, positionPct)
            return
        }

        // ── Ведение открытой позиции ──
        p.highestPrice = max(p.highestPrice, price)

        if (p.posType == "MOM") {
            // Перевод в безубыток после хода на 1×стоп-дистанцию.
            if (!p.breakeven && price >= p.entryPrice + p.trailDistance) {
                p.stopPrice = max(p.stopPrice, p.entryPrice * 1.002)
                p.breakeven = true
                log("${p.symbol}: стоп переведён в безубыток")
            }
            // Трейлинг «люстра» от максимума.
            p.stopPrice = max(p.stopPrice, p.highestPrice - p.trailDistance)
        }
        savePositions(pairs)

        val exitReason = when {
            price <= p.stopPrice -> "стоп-лосс"
            p.targetPrice > 0 && price >= p.targetPrice -> "цель: возврат к средней"
            newCandle && p.posType == "MOM" && Strategy.momentumExitSignal(closed) -> "слом импульса"
            newCandle && p.posType == "REV" && Strategy.reversionExitSignal(closed) -> "перепроданность снята"
            newCandle && p.posType == "MOM" && p.barsHeld >= MOM_TIME_STOP_BARS && price < p.entryPrice ->
                "тайм-стоп: движение не пошло"
            newCandle && p.posType == "REV" && p.barsHeld >= REV_TIME_STOP_BARS ->
                "тайм-стоп: возврата не случилось"
            else -> null
        }
        if (exitReason != null) trySell(client, p, pairs, price, exitReason)
    }

    private fun tryBuy(
        client: BinanceClient,
        p: PairState,
        pairs: List<PairState>,
        plan: EntryPlan,
        positionPct: Double
    ) {
        val free = client.freeBalance(p.rules.quoteAsset)
        val invested = pairs.filter { it.inPosition }.sumOf { it.spentQuote }
        val equity = free + invested

        // Риск-паритет: qty = риск / стоп-дистанция → notional = qty × цена.
        val price = client.lastPrice(p.symbol)
        val riskAmount = equity * RISK_PCT / 100.0
        val notionalByRisk = riskAmount / plan.stopDistance * price
        val cap = free * positionPct / 100.0
        val spend = min(notionalByRisk, cap)

        if (spend < p.rules.minNotional.toDouble() || spend < 5.0 || spend > free) {
            log(
                "%s: сигнал (%s), но сумма %.2f %s вне лимитов (свободно %.2f)"
                    .format(Locale.US, p.symbol, plan.reason, spend, p.rules.quoteAsset, free)
            )
            return
        }

        val fill = client.marketBuy(p.symbol, spend)
        if (fill.executedQty <= 0) {
            log("${p.symbol}: ордер BUY не исполнился")
            return
        }
        p.inPosition = true
        p.posType = if (plan.type == TradeType.MOMENTUM) "MOM" else "REV"
        p.entryPrice = fill.avgPrice
        p.qty = fill.executedQty
        p.spentQuote = fill.quoteSpent
        p.highestPrice = fill.avgPrice
        p.trailDistance = plan.stopDistance
        p.stopPrice = fill.avgPrice - plan.stopDistance
        p.targetPrice = plan.target
        p.barsHeld = 0
        p.breakeven = false
        savePositions(pairs)

        val typeName = if (p.posType == "MOM") "ПРОБОЙ" else "ВЫКУП"
        log(
            "✅ %s [%s, счёт %d]: куплено %.8g по %.6g (%.2f %s). Стоп %.6g%s — %s"
                .format(
                    Locale.US, p.symbol, typeName, plan.score, p.qty, p.entryPrice,
                    fill.quoteSpent, p.rules.quoteAsset, p.stopPrice,
                    if (p.targetPrice > 0) ", цель %.6g".format(Locale.US, p.targetPrice) else " (трейлинг)",
                    plan.reason
                )
        )
        notifyUser(
            "Куплено ${p.symbol} ($typeName)",
            "Вход %.6g, стоп %.6g".format(Locale.US, p.entryPrice, p.stopPrice)
        )
    }

    private fun trySell(
        client: BinanceClient,
        p: PairState,
        pairs: List<PairState>,
        price: Double,
        reason: String
    ) {
        // Продаём фактический свободный остаток (комиссия могла списаться в базовой валюте).
        val freeBase = client.freeBalance(p.rules.baseAsset)
        val sellQty = BinanceClient.roundToStep(minOf(p.qty, freeBase), p.rules.stepSize)
        if (sellQty < p.rules.minQty || sellQty.toDouble() * price < p.rules.minNotional.toDouble()) {
            log(
                "%s: остаток %.8g ниже минимума — сбрасываю позицию"
                    .format(Locale.US, p.symbol, freeBase)
            )
            clearPosition(p, pairs)
            return
        }
        val fill = client.marketSell(p.symbol, sellQty)
        val pnlQuote = fill.quoteSpent - p.spentQuote
        val pnlPct = (fill.avgPrice - p.entryPrice) / p.entryPrice * 100

        dayRealizedPnl += pnlQuote
        checkDailyLoss()
        if (reason == "стоп-лосс" && pnlQuote < 0) {
            p.cooldownBars = COOLDOWN_BARS
            log("${p.symbol}: кулдаун $COOLDOWN_BARS свечей после стопа")
        }

        log(
            "🔻 %s: продано %.8g по %.6g (%s). Результат: %+.2f%% (%+.2f %s)"
                .format(
                    Locale.US, p.symbol, fill.executedQty, fill.avgPrice, reason,
                    pnlPct, pnlQuote, p.rules.quoteAsset
                )
        )
        notifyUser(
            "Продано ${p.symbol} (%+.2f%%)".format(Locale.US, pnlPct),
            "Причина: $reason, выход %.6g".format(Locale.US, fill.avgPrice)
        )
        clearPosition(p, pairs)
        saveDayRisk()
    }

    // ── Дневной риск-контроль ──

    private fun utcDayKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun rollDayIfNeeded(client: BinanceClient, pairs: List<PairState>) {
        val today = utcDayKey()
        if (today == dayKey) return
        dayKey = today
        dayRealizedPnl = 0.0
        dailyLossHit = false
        dayStartEquity = try {
            val quote = pairs.firstOrNull()?.rules?.quoteAsset ?: "USDT"
            client.freeBalance(quote) + pairs.filter { it.inPosition }.sumOf { it.spentQuote }
        } catch (_: Exception) {
            0.0
        }
        saveDayRisk()
        log("Новый торговый день (UTC). Капитал: %.2f".format(Locale.US, dayStartEquity))
    }

    private fun checkDailyLoss() {
        if (dailyLossHit || dayStartEquity <= 0) return
        if (dayRealizedPnl <= -dayStartEquity * DAILY_LOSS_PCT / 100.0) {
            dailyLossHit = true
            log(
                "⛔ Дневной лимит убытка $DAILY_LOSS_PCT%% достигнут (%.2f). Новые входы — завтра."
                    .format(Locale.US, dayRealizedPnl)
            )
            notifyUser("Дневной лимит убытка", "Бот не будет открывать сделки до завтра (UTC)")
        }
    }

    private fun saveDayRisk() {
        Prefs.putString(
            this, "dayRisk",
            JSONObject()
                .put("dayKey", dayKey)
                .put("dayStartEquity", dayStartEquity)
                .put("dayRealizedPnl", dayRealizedPnl)
                .put("dailyLossHit", dailyLossHit)
                .toString()
        )
    }

    private fun loadDayRisk() {
        try {
            val s = Prefs.getString(this, "dayRisk", "")
            if (s.isEmpty()) return
            val j = JSONObject(s)
            if (j.optString("dayKey") == utcDayKey()) {
                dayKey = j.optString("dayKey")
                dayStartEquity = j.optDouble("dayStartEquity", 0.0)
                dayRealizedPnl = j.optDouble("dayRealizedPnl", 0.0)
                dailyLossHit = j.optBoolean("dailyLossHit", false)
            }
        } catch (_: Exception) {
        }
    }

    // ── Формирование списка пар ──

    private fun buildPairs(
        client: BinanceClient,
        autoMode: Boolean,
        manualSymbols: List<String>
    ): MutableList<PairState> {
        val saved = loadPositions()
        val pairs = mutableListOf<PairState>()

        if (autoMode) {
            val rules = client.allUsdtRules()
            val top = client.topByVolume(rules, AUTO_WATCHLIST)
            val withOpen = saved.keys.filter { it !in top }
            for (sym in top + withOpen) {
                pairs.add(PairState(sym, rules[sym] ?: client.symbolRules(sym)))
            }
        } else {
            for (sym in manualSymbols) {
                pairs.add(PairState(sym, client.symbolRules(sym)))
            }
        }

        for (p in pairs) {
            saved[p.symbol]?.let { j ->
                p.inPosition = j.optBoolean("inPosition", false)
                p.posType = j.optString("posType", "MOM")
                p.entryPrice = j.optDouble("entryPrice", 0.0)
                p.qty = j.optDouble("qty", 0.0)
                p.spentQuote = j.optDouble("spentQuote", 0.0)
                p.stopPrice = j.optDouble("stopPrice", 0.0)
                p.targetPrice = j.optDouble("targetPrice", 0.0)
                p.trailDistance = j.optDouble("trailDistance", 0.0)
                p.highestPrice = j.optDouble("highestPrice", 0.0)
                p.barsHeld = j.optInt("barsHeld", 0)
                p.breakeven = j.optBoolean("breakeven", false)
                if (p.inPosition) {
                    log(
                        "%s: восстановлена позиция [%s] — вход %.6g, стоп %.6g"
                            .format(Locale.US, p.symbol, p.posType, p.entryPrice, p.stopPrice)
                    )
                }
            }
        }
        if (pairs.isEmpty()) throw IllegalStateException("Список пар пуст")
        return pairs
    }

    private fun refreshAutoPairs(
        client: BinanceClient,
        current: MutableList<PairState>
    ): MutableList<PairState> {
        val rules = client.allUsdtRules()
        val top = client.topByVolume(rules, AUTO_WATCHLIST)
        val result = mutableListOf<PairState>()
        // Открытые позиции переносим как есть — их надо довести до выхода.
        for (p in current) {
            if (p.inPosition) result.add(p)
        }
        for (sym in top) {
            if (result.none { it.symbol == sym }) {
                val existing = current.find { it.symbol == sym }
                result.add(existing ?: PairState(sym, rules[sym] ?: continue))
            }
        }
        return result
    }

    // ── Персистентность позиций ──

    private fun savePositions(pairsSnapshot: List<PairState>) {
        val root = JSONObject()
        for (p in pairsSnapshot) {
            if (!p.inPosition) continue
            root.put(
                p.symbol,
                JSONObject()
                    .put("inPosition", true)
                    .put("posType", p.posType)
                    .put("entryPrice", p.entryPrice)
                    .put("qty", p.qty)
                    .put("spentQuote", p.spentQuote)
                    .put("stopPrice", p.stopPrice)
                    .put("targetPrice", p.targetPrice)
                    .put("trailDistance", p.trailDistance)
                    .put("highestPrice", p.highestPrice)
                    .put("barsHeld", p.barsHeld)
                    .put("breakeven", p.breakeven)
            )
        }
        Prefs.putString(this, "positions", root.toString())
    }

    private fun loadPositions(): Map<String, JSONObject> {
        return try {
            val s = Prefs.getString(this, "positions", "")
            if (s.isEmpty()) return emptyMap()
            val root = JSONObject(s)
            val out = HashMap<String, JSONObject>()
            for (key in root.keys()) out[key] = root.getJSONObject(key)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun clearPosition(p: PairState, pairs: List<PairState>) {
        p.inPosition = false
        p.posType = ""
        p.entryPrice = 0.0
        p.qty = 0.0
        p.spentQuote = 0.0
        p.stopPrice = 0.0
        p.targetPrice = 0.0
        p.trailDistance = 0.0
        p.highestPrice = 0.0
        p.barsHeld = 0
        p.breakeven = false
        savePositions(pairs)
    }

    // ── Служебное ──

    private fun updateStatus(pairs: List<PairState>, btcNotBear: Boolean) {
        val open = pairs.filter { it.inPosition }
        val line = when {
            open.isNotEmpty() -> {
                val parts = open.map { p ->
                    val peak =
                        if (p.entryPrice > 0) (p.highestPrice - p.entryPrice) / p.entryPrice * 100 else 0.0
                    "%s[%s] пик %+.1f%%".format(Locale.US, p.symbol, p.posType, peak)
                }
                "Позиции ${open.size}: ${parts.joinToString(", ")}"
            }
            dailyLossHit -> "⛔ Дневной лимит убытка — входы завтра"
            !btcNotBear -> "Медвежий рынок (BTC < SMA200) — жду"
            else -> "Ждём сигнал | слежу за ${pairs.size} парами"
        }
        BotState.setStatusLine(line)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(line))
    }

    private fun stopLoop(finalStatus: String) {
        loopJob?.cancel()
        loopJob = null
        BotState.setRunning(false)
        BotState.setStatusLine(finalStatus)
        log("── Бот остановлен ──")
    }

    private fun log(line: String) {
        BotState.log(line)
        try {
            val f = File(filesDir, "trades.log")
            if (f.length() > 1_000_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Торговый бот", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Binance Trader")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyUser(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt() + 100, n)
    }
}
