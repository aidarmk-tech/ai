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
import java.util.Locale
import kotlin.math.max

/** Состояние одной торговой пары: индикаторы + открытая позиция. */
private class PairState(
    val symbol: String,
    val rules: SymbolRules,
    var lastClosedTime: Long = 0L,
    var lastAtr: Double = 0.0,
    var inPosition: Boolean = false,
    var entryPrice: Double = 0.0,
    var qty: Double = 0.0,
    var stopPrice: Double = 0.0,
    var takePrice: Double = 0.0,
    var highestPrice: Double = 0.0
)

/**
 * Foreground-сервис: торгует сразу несколькими парами.
 * Список пар либо задан вручную, либо (режим AUTO) выбирается автоматически —
 * топ по обороту за 24 часа, с пересмотром каждые 6 часов.
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
        log("── Бот запущен ──")

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
                if (autoMode) "Режим пар: АВТО (топ-$AUTO_WATCHLIST по обороту, пересмотр каждые 6 ч)"
                else "Пары: ${manualSymbols.joinToString(", ")}"
            )
            log("Таймфрейм: $interval, на сделку: ${positionPct.toInt()}% депозита, макс. позиций: $maxPositions")
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

            var lastAutoRefresh = System.currentTimeMillis()
            var errorStreak = 0

            while (isActive) {
                // Периодический пересмотр авто-списка (открытые позиции не выбрасываем).
                if (autoMode && System.currentTimeMillis() - lastAutoRefresh > AUTO_REFRESH_MS) {
                    try {
                        pairs = refreshAutoPairs(client, pairs)
                        lastAutoRefresh = System.currentTimeMillis()
                        log("Список пар обновлён: ${pairs.joinToString(", ") { it.symbol }}")
                    } catch (e: Exception) {
                        log("Не удалось обновить список пар: ${e.message}")
                    }
                }

                var cycleFailed = false
                for (p in pairs) {
                    if (!isActive) break
                    try {
                        processPair(client, p, pairs, interval, positionPct, maxPositions)
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

                if (cycleFailed) {
                    errorStreak++
                    if (errorStreak >= 3) {
                        try {
                            client.syncTime()
                        } catch (_: Exception) {
                        }
                        errorStreak = 0
                    }
                } else {
                    errorStreak = 0
                }

                updateStatus(pairs)
                delay(POLL_MS)
            }
        }
    }

    /** Один проход по паре: новая свеча → сигналы; между свечами → стоп/тейк. */
    private fun processPair(
        client: BinanceClient,
        p: PairState,
        pairs: List<PairState>,
        interval: String,
        positionPct: Double,
        maxPositions: Int
    ) {
        val now = System.currentTimeMillis()
        val closed = client.klines(p.symbol, interval, 120).filter { it.closeTime <= now }
        if (closed.size < Strategy.MIN_CANDLES) return

        val price = client.lastPrice(p.symbol)
        val newCandle = closed.last().closeTime != p.lastClosedTime
        var result: StrategyResult? = null

        if (newCandle) {
            result = Strategy.evaluate(closed)
            p.lastClosedTime = closed.last().closeTime
            p.lastAtr = result.atr
            if (result.signal != Signal.HOLD) {
                log(
                    "%s: свеча закрыта, цена=%.6g RSI=%.1f → %s"
                        .format(Locale.US, p.symbol, closed.last().close, result.rsi, result.signal)
                )
            }
        }

        if (!p.inPosition) {
            val openCount = pairs.count { it.inPosition }
            if (newCandle && result?.signal == Signal.BUY && openCount < maxPositions) {
                tryBuy(client, p, positionPct, result.atr)
            }
        } else {
            p.highestPrice = max(p.highestPrice, price)
            if (p.lastAtr > 0) {
                p.stopPrice = max(p.stopPrice, p.highestPrice - Strategy.STOP_ATR * p.lastAtr)
            }
            savePositions(pairsSnapshot = pairs)

            val exitReason = when {
                price <= p.stopPrice -> "стоп-лосс"
                price >= p.takePrice -> "тейк-профит"
                newCandle && result?.signal == Signal.SELL -> "EMA-кросс вниз"
                else -> null
            }
            if (exitReason != null) {
                trySell(client, p, pairs, price, exitReason)
            }
        }
    }

    private fun tryBuy(client: BinanceClient, p: PairState, positionPct: Double, atr: Double) {
        val free = client.freeBalance(p.rules.quoteAsset)
        val spend = free * positionPct / 100.0
        if (spend < p.rules.minNotional.toDouble() || spend < 5.0) {
            log(
                "%s: сигнал BUY, но мало %s (свободно %.2f)"
                    .format(Locale.US, p.symbol, p.rules.quoteAsset, free)
            )
            return
        }
        val fill = client.marketBuy(p.symbol, spend)
        if (fill.executedQty <= 0) {
            log("${p.symbol}: ордер BUY не исполнился")
            return
        }
        p.inPosition = true
        p.entryPrice = fill.avgPrice
        p.qty = fill.executedQty
        p.highestPrice = fill.avgPrice
        p.stopPrice = fill.avgPrice - Strategy.STOP_ATR * atr
        p.takePrice = fill.avgPrice + Strategy.TAKE_ATR * atr
        log(
            "✅ %s: куплено %.8g по %.6g (%.2f %s). Стоп %.6g, тейк %.6g"
                .format(
                    Locale.US, p.symbol, p.qty, p.entryPrice, fill.quoteSpent,
                    p.rules.quoteAsset, p.stopPrice, p.takePrice
                )
        )
        notifyUser("Куплено ${p.symbol}", "Вход %.6g, стоп %.6g".format(Locale.US, p.entryPrice, p.stopPrice))
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
        val pnl = (fill.avgPrice - p.entryPrice) / p.entryPrice * 100
        log(
            "🔻 %s: продано %.8g по %.6g (%s). Результат: %+.2f%%"
                .format(Locale.US, p.symbol, fill.executedQty, fill.avgPrice, reason, pnl)
        )
        notifyUser(
            "Продано ${p.symbol} (%+.2f%%)".format(Locale.US, pnl),
            "Причина: $reason, выход %.6g".format(Locale.US, fill.avgPrice)
        )
        clearPosition(p, pairs)
    }

    // ── Формирование списка пар ──

    private fun buildPairs(
        client: BinanceClient,
        autoMode: Boolean,
        manualSymbols: List<String>
    ): MutableList<PairState> {
        val saved = loadPositions()
        val pairs = mutableListOf<PairState>()

        val symbols: List<String> = if (autoMode) {
            val rules = client.allUsdtRules()
            val top = client.topByVolume(rules, AUTO_WATCHLIST)
            // Пары с сохранённой открытой позицией держим, даже если выпали из топа.
            val withOpen = saved.keys.filter { it !in top }
            (top + withOpen).map { sym ->
                pairs.add(PairState(sym, rules[sym] ?: client.symbolRules(sym)))
                sym
            }
        } else {
            manualSymbols.map { sym ->
                pairs.add(PairState(sym, client.symbolRules(sym)))
                sym
            }
        }

        for (p in pairs) {
            saved[p.symbol]?.let { j ->
                p.inPosition = j.optBoolean("inPosition", false)
                p.entryPrice = j.optDouble("entryPrice", 0.0)
                p.qty = j.optDouble("qty", 0.0)
                p.stopPrice = j.optDouble("stopPrice", 0.0)
                p.takePrice = j.optDouble("takePrice", 0.0)
                p.highestPrice = j.optDouble("highestPrice", 0.0)
                p.lastAtr = j.optDouble("lastAtr", 0.0)
                if (p.inPosition) {
                    log(
                        "%s: восстановлена позиция — вход %.6g, стоп %.6g"
                            .format(Locale.US, p.symbol, p.entryPrice, p.stopPrice)
                    )
                }
            }
        }
        if (symbols.isEmpty()) throw IllegalStateException("Список пар пуст")
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

    // ── Персистентность позиций (все пары одним JSON-объектом) ──

    private fun savePositions(pairsSnapshot: List<PairState>) {
        val root = JSONObject()
        for (p in pairsSnapshot) {
            if (!p.inPosition) continue
            root.put(
                p.symbol,
                JSONObject()
                    .put("inPosition", true)
                    .put("entryPrice", p.entryPrice)
                    .put("qty", p.qty)
                    .put("stopPrice", p.stopPrice)
                    .put("takePrice", p.takePrice)
                    .put("highestPrice", p.highestPrice)
                    .put("lastAtr", p.lastAtr)
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
        p.entryPrice = 0.0
        p.qty = 0.0
        p.stopPrice = 0.0
        p.takePrice = 0.0
        p.highestPrice = 0.0
        savePositions(pairs)
    }

    // ── Служебное ──

    private fun updateStatus(pairs: List<PairState>) {
        val open = pairs.filter { it.inPosition }
        val line = if (open.isEmpty()) {
            "Ждём сигнал | слежу за ${pairs.size} парами"
        } else {
            val parts = open.map { p ->
                val pnl =
                    if (p.entryPrice > 0 && p.highestPrice > 0)
                        (p.highestPrice - p.entryPrice) / p.entryPrice * 100
                    else 0.0
                "%s (пик %+.1f%%)".format(Locale.US, p.symbol, pnl)
            }
            "Позиции ${open.size}: ${parts.joinToString(", ")}"
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
