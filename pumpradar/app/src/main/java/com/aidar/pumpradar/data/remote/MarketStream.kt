package com.aidar.pumpradar.data.remote

import com.aidar.pumpradar.domain.model.MiniTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Поток !miniTicker@arr с устойчивым переподключением (ТЗ раздел 18):
 * exponential backoff + jitter, переключение портов 443/9443, повторная
 * подписка после reconnect. Общерыночный сканер.
 */
@Singleton
class MarketStream @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var connectedAt = 0L
    private var attempt = 0
    private var useAlt = false
    private var scope: CoroutineScope? = null

    private var onTickers: ((List<MiniTicker>) -> Unit)? = null
    private var onConnected: ((Boolean) -> Unit)? = null
    private var onReconnect: (() -> Unit)? = null

    fun start(
        scope: CoroutineScope,
        onTickers: (List<MiniTicker>) -> Unit,
        onConnected: (Boolean) -> Unit,
        onReconnect: () -> Unit
    ) {
        this.scope = scope
        this.onTickers = onTickers
        this.onConnected = onConnected
        this.onReconnect = onReconnect
        running = true
        attempt = 0
        connect()
        startRotationWatchdog(scope)
    }

    fun stop() {
        running = false
        connectedAt = 0L
        ws?.close(1000, "stopped")
        ws = null
    }

    /**
     * Проактивная ротация соединения до жёсткого лимита Binance ~24ч (ТЗ 0A.6):
     * закрываем соединение по возрасту, штатный reconnect тут же переподключает
     * (для !miniTicker@arr переподписка не нужна — поток зашит в URL).
     */
    private fun startRotationWatchdog(scope: CoroutineScope) {
        scope.launch {
            while (running) {
                delay(60_000)
                val c = connectedAt
                if (running && c > 0 && System.currentTimeMillis() - c > PROACTIVE_ROTATION_MS) {
                    Timber.i("Проактивная ротация market WS по возрасту")
                    connectedAt = 0L
                    ws?.close(1000, "rotate")
                }
            }
        }
    }

    private fun connect() {
        val host = if (useAlt) "wss://stream.binance.com:9443" else "wss://stream.binance.com:443"
        val req = Request.Builder().url("$host/stream?streams=!miniTicker@arr").build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            connectedAt = System.currentTimeMillis()
            onConnected?.invoke(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val root = json.parseToJsonElement(text).jsonObject
                val dataEl = root["data"] ?: return
                val list = json.decodeFromJsonElement(
                    ListSerializer(MiniTickerDto.serializer()), dataEl
                )
                val tickers = list.mapNotNull { it.toModel() }
                if (tickers.isNotEmpty()) onTickers?.invoke(tickers)
            } catch (e: Exception) {
                Timber.w(e, "miniTicker parse failed")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onConnected?.invoke(false)
            scheduleReconnect("failure: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnected?.invoke(false)
            if (running) scheduleReconnect("closed: $code")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        attempt++
        useAlt = !useAlt // чередуем 443/9443
        onReconnect?.invoke()
        val base = BACKOFF_SECONDS[attempt.coerceAtMost(BACKOFF_SECONDS.size - 1)]
        val jitter = 1.0 + Random.nextDouble(-0.2, 0.2)
        val delayMs = (base * 1000 * jitter).toLong()
        Timber.d("WS reconnect in ${delayMs}ms ($reason)")
        scope?.launch {
            delay(delayMs)
            if (running) connect()
        }
    }

    companion object {
        private val BACKOFF_SECONDS = intArrayOf(1, 1, 2, 4, 8, 15, 30, 60)
        // Ротация до жёсткого лимита Binance 24ч (ТЗ 0A.6): 23ч45м.
        private const val PROACTIVE_ROTATION_MS = 23L * 60 * 60 * 1000 + 45L * 60 * 1000
    }
}
