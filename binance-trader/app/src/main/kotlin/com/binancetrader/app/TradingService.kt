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

/**
 * Foreground-сервис: крутит торговый цикл, пока пользователь не остановит.
 * Раз в POLL_MS опрашивает свечи/цену, на закрытии новой свечи считает сигналы,
 * между свечами следит за стопом и тейком по последней цене.
 */
class TradingService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "trading"
        private const val NOTIF_ID = 1
        private const val POLL_MS = 20_000L

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

    // ── Позиция (переживает перезапуск через Prefs) ──
    private var inPosition = false
    private var entryPrice = 0.0
    private var qty = 0.0
    private var stopPrice = 0.0
    private var takePrice = 0.0
    private var highestPrice = 0.0

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
        loadPosition()
        BotState.setRunning(true)
        log("── Бот запущен ──")

        loopJob = scope.launch {
            val symbol = Prefs.getString(this@TradingService, "symbol", "BTCUSDT").uppercase(Locale.US)
            val interval = Prefs.getString(this@TradingService, "interval", "15m")
            val positionPct = Prefs.getString(this@TradingService, "positionPct", "20").toDoubleOrNull() ?: 20.0
            val testnet = Prefs.getBoolean(this@TradingService, "testnet", true)

            val client = BinanceClient(Prefs.apiKey, Prefs.apiSecret, testnet)

            log("Пара: $symbol, таймфрейм: $interval, размер позиции: ${positionPct.toInt()}% депозита")
            log(if (testnet) "Режим: ТЕСТНЕТ (виртуальные деньги)" else "⚠ Режим: РЕАЛЬНАЯ ТОРГОВЛЯ")

            val rules = try {
                client.syncTime()
                client.symbolRules(symbol)
            } catch (e: Exception) {
                log("Ошибка инициализации: ${e.message}")
                stopLoop("Ошибка: ${e.message}")
                stopSelf()
                return@launch
            }

            var lastClosedTime = 0L
            var lastAtr = 0.0
            var errorStreak = 0

            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val all = client.klines(symbol, interval, 200)
                    val closed = all.filter { it.closeTime <= now }
                    if (closed.size < Strategy.MIN_CANDLES) {
                        log("Мало истории (${closed.size} свечей), жду…")
                        delay(POLL_MS)
                        continue
                    }

                    val price = client.lastPrice(symbol)
                    val newCandle = closed.last().closeTime != lastClosedTime
                    var result: StrategyResult? = null

                    if (newCandle) {
                        result = Strategy.evaluate(closed)
                        lastClosedTime = closed.last().closeTime
                        lastAtr = result.atr
                        log(
                            "Свеча закрыта: цена=%.6g RSI=%.1f EMA9=%.6g EMA21=%.6g → %s"
                                .format(Locale.US, closed.last().close, result.rsi, result.ema9, result.ema21, result.signal)
                        )
                    }

                    if (!inPosition) {
                        if (newCandle && result?.signal == Signal.BUY) {
                            tryBuy(client, symbol, rules, positionPct, result.atr, price)
                        }
                        status("Ждём сигнал | $symbol %.6g".format(Locale.US, price))
                    } else {
                        // Трейлинг-стоп: подтягиваем вверх за ценой, вниз не опускаем.
                        highestPrice = max(highestPrice, price)
                        if (lastAtr > 0) {
                            stopPrice = max(stopPrice, highestPrice - Strategy.STOP_ATR * lastAtr)
                        }
                        savePosition()

                        val exitReason = when {
                            price <= stopPrice -> "стоп-лосс"
                            price >= takePrice -> "тейк-профит"
                            newCandle && result?.signal == Signal.SELL -> "сигнал на выход (EMA-кросс вниз)"
                            else -> null
                        }
                        if (exitReason != null) {
                            trySell(client, symbol, rules, price, exitReason)
                        } else {
                            val pnl = (price - entryPrice) / entryPrice * 100
                            status(
                                "В позиции | вход %.6g | сейчас %.6g (%+.2f%%) | стоп %.6g | тейк %.6g"
                                    .format(Locale.US, entryPrice, price, pnl, stopPrice, takePrice)
                            )
                        }
                    }
                    errorStreak = 0
                } catch (e: Exception) {
                    errorStreak++
                    log("Ошибка (${errorStreak}): ${e.message}")
                    if (e is BinanceException && (e.code == -2014 || e.code == -2015 || e.code == -1022)) {
                        log("Ключи API не подходят — останавливаюсь.")
                        stopLoop("Неверные ключи API")
                        stopSelf()
                        return@launch
                    }
                    if (errorStreak >= 3) {
                        try {
                            client.syncTime()
                        } catch (_: Exception) {
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun tryBuy(
        client: BinanceClient,
        symbol: String,
        rules: SymbolRules,
        positionPct: Double,
        atr: Double,
        price: Double
    ) {
        val free = client.freeBalance(rules.quoteAsset)
        val spend = free * positionPct / 100.0
        if (spend < rules.minNotional.toDouble() || spend < 5.0) {
            log("Сигнал BUY, но недостаточно ${rules.quoteAsset}: свободно %.2f, нужно ≥ %.2f".format(
                Locale.US, free, max(rules.minNotional.toDouble(), 5.0)))
            return
        }
        val fill = client.marketBuy(symbol, spend)
        if (fill.executedQty <= 0) {
            log("Ордер BUY не исполнился")
            return
        }
        inPosition = true
        entryPrice = fill.avgPrice
        qty = fill.executedQty
        highestPrice = fill.avgPrice
        stopPrice = fill.avgPrice - Strategy.STOP_ATR * atr
        takePrice = fill.avgPrice + Strategy.TAKE_ATR * atr
        savePosition()
        log(
            "✅ КУПЛЕНО %.8g %s по %.6g (потрачено %.2f %s). Стоп %.6g, тейк %.6g"
                .format(Locale.US, qty, rules.baseAsset, entryPrice, fill.quoteSpent, rules.quoteAsset, stopPrice, takePrice)
        )
        notifyUser("Куплено $symbol", "Вход %.6g, стоп %.6g".format(Locale.US, entryPrice, stopPrice))
    }

    private fun trySell(
        client: BinanceClient,
        symbol: String,
        rules: SymbolRules,
        price: Double,
        reason: String
    ) {
        // Продаём фактический свободный остаток (комиссия могла списаться в базовой валюте).
        val freeBase = client.freeBalance(rules.baseAsset)
        val sellQty = BinanceClient.roundToStep(minOf(qty, freeBase), rules.stepSize)
        if (sellQty < rules.minQty || sellQty.toDouble() * price < rules.minNotional.toDouble()) {
            log("Нечего продавать (остаток %.8g %s ниже минимума) — сбрасываю позицию".format(
                Locale.US, freeBase, rules.baseAsset))
            clearPosition()
            return
        }
        val fill = client.marketSell(symbol, sellQty)
        val pnl = (fill.avgPrice - entryPrice) / entryPrice * 100
        log(
            "🔻 ПРОДАНО %.8g %s по %.6g (%s). Результат: %+.2f%%"
                .format(Locale.US, fill.executedQty, rules.baseAsset, fill.avgPrice, reason, pnl)
        )
        notifyUser(
            "Продано $symbol (%+.2f%%)".format(Locale.US, pnl),
            "Причина: $reason, выход %.6g".format(Locale.US, fill.avgPrice)
        )
        clearPosition()
    }

    // ── Персистентность позиции ──

    private fun savePosition() {
        val j = JSONObject()
            .put("inPosition", inPosition)
            .put("entryPrice", entryPrice)
            .put("qty", qty)
            .put("stopPrice", stopPrice)
            .put("takePrice", takePrice)
            .put("highestPrice", highestPrice)
        Prefs.putString(this, "position", j.toString())
    }

    private fun loadPosition() {
        try {
            val s = Prefs.getString(this, "position", "")
            if (s.isEmpty()) return
            val j = JSONObject(s)
            inPosition = j.optBoolean("inPosition", false)
            entryPrice = j.optDouble("entryPrice", 0.0)
            qty = j.optDouble("qty", 0.0)
            stopPrice = j.optDouble("stopPrice", 0.0)
            takePrice = j.optDouble("takePrice", 0.0)
            highestPrice = j.optDouble("highestPrice", 0.0)
            if (inPosition) {
                log("Восстановлена открытая позиция: вход %.6g, кол-во %.8g".format(Locale.US, entryPrice, qty))
            }
        } catch (_: Exception) {
        }
    }

    private fun clearPosition() {
        inPosition = false
        entryPrice = 0.0
        qty = 0.0
        stopPrice = 0.0
        takePrice = 0.0
        highestPrice = 0.0
        savePosition()
    }

    // ── Служебное ──

    private fun stopLoop(finalStatus: String) {
        loopJob?.cancel()
        loopJob = null
        BotState.setRunning(false)
        BotState.setStatusLine(finalStatus)
        log("── Бот остановлен ──")
    }

    private fun status(line: String) {
        BotState.setStatusLine(line)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(line))
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
