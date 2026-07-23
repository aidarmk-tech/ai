package com.aidar.pumpradar.data.remote

import com.aidar.pumpradar.domain.model.AggTrade
import com.aidar.pumpradar.domain.model.BookTicker
import com.aidar.pumpradar.domain.model.PartialDepth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
 * Соединение для углублённых потоков кандидатов (ТЗ раздел 8.2, 17):
 * динамические SUBSCRIBE/UNSUBSCRIBE на <symbol>@aggTrade и <symbol>@bookTicker.
 */
@Singleton
class CandidateStream @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    private var attempt = 0
    private var useAlt = false
    private var scope: CoroutineScope? = null
    private var reqId = 1

    private val desired = LinkedHashSet<String>()   // потоки: btcusdt@aggTrade и т.п.
    private val current = LinkedHashSet<String>()

    private var onAggTrade: ((AggTrade) -> Unit)? = null
    private var onBookTicker: ((BookTicker) -> Unit)? = null
    private var onDepth: ((PartialDepth) -> Unit)? = null
    private var onConnected: ((Boolean) -> Unit)? = null

    fun start(
        scope: CoroutineScope,
        onAggTrade: (AggTrade) -> Unit,
        onBookTicker: (BookTicker) -> Unit,
        onDepth: (PartialDepth) -> Unit,
        onConnected: (Boolean) -> Unit
    ) {
        this.scope = scope
        this.onAggTrade = onAggTrade
        this.onBookTicker = onBookTicker
        this.onDepth = onDepth
        this.onConnected = onConnected
        running = true
        attempt = 0
        connect()
    }

    fun stop() {
        running = false
        connected = false
        desired.clear()
        current.clear()
        ws?.close(1000, "stopped")
        ws = null
    }

    /** Задать желаемый набор потоков (aggTrade/bookTicker/depth) напрямую. */
    @Synchronized
    fun setDesiredStreams(streams: Set<String>) {
        desired.clear()
        desired.addAll(streams)
        if (connected) reconcile()
    }

    @Synchronized
    private fun reconcile() {
        val socket = ws ?: return
        val toAdd = desired - current
        val toRemove = current - desired
        if (toAdd.isNotEmpty()) {
            socket.send(command("SUBSCRIBE", toAdd.toList()))
            current.addAll(toAdd)
        }
        if (toRemove.isNotEmpty()) {
            socket.send(command("UNSUBSCRIBE", toRemove.toList()))
            current.removeAll(toRemove)
        }
    }

    private fun command(method: String, params: List<String>): String {
        val obj = buildJsonObject {
            put("method", method)
            put("params", buildJsonArray { params.forEach { add(it) } })
            put("id", reqId++)
        }
        return obj.toString()
    }

    private fun connect() {
        val host = if (useAlt) "wss://stream.binance.com:9443" else "wss://stream.binance.com:443"
        val req = Request.Builder().url("$host/stream").build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            connected = true
            current.clear()
            onConnected?.invoke(true)
            reconcile() // повторная подписка после reconnect
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val root = json.parseToJsonElement(text).jsonObject
                val stream = (root["stream"] as? JsonPrimitive)?.content ?: return
                val data = root["data"] ?: return
                when {
                    stream.endsWith("@aggTrade") -> {
                        val sym = stream.substringBefore("@").uppercase()
                        val dto = json.decodeFromJsonElement(AggTradeDto.serializer(), data)
                        dto.toModel(sym)?.let { onAggTrade?.invoke(it) }
                    }
                    stream.endsWith("@bookTicker") -> {
                        val sym = stream.substringBefore("@").uppercase()
                        val dto = json.decodeFromJsonElement(BookTickerDto.serializer(), data)
                        dto.toModel(sym)?.let { onBookTicker?.invoke(it) }
                    }
                    stream.contains("@depth20") -> {
                        val sym = stream.substringBefore("@").uppercase()
                        val dto = json.decodeFromJsonElement(DepthDto.serializer(), data)
                        onDepth?.invoke(dto.toModel(sym))
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "candidate parse failed")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            onConnected?.invoke(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            onConnected?.invoke(false)
            if (running) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        attempt++
        useAlt = !useAlt
        val base = BACKOFF_SECONDS[attempt.coerceAtMost(BACKOFF_SECONDS.size - 1)]
        val delayMs = (base * 1000 * (1.0 + Random.nextDouble(-0.2, 0.2))).toLong()
        scope?.launch {
            delay(delayMs)
            if (running) connect()
        }
    }

    companion object {
        private val BACKOFF_SECONDS = intArrayOf(1, 1, 2, 4, 8, 15, 30, 60)
    }
}
