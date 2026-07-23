package com.aidar.pumpradar.data.remote

import com.aidar.pumpradar.domain.model.SymbolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ExchangeInfoDto(val symbols: List<SymbolDto> = emptyList())

@Serializable
private data class SymbolDto(
    val symbol: String,
    val status: String = "",
    val baseAsset: String = "",
    val quoteAsset: String = "",
    val isSpotTradingAllowed: Boolean = true
)

@Serializable
private data class ServerTimeDto(@SerialName("serverTime") val serverTime: Long = 0)

/** Публичный REST Binance Spot (без ключа). ТЗ раздел 6.1. */
@Singleton
class BinanceRest @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    private val base = "https://api.binance.com"
    private val fallback = "https://data-api.binance.vision"

    /** Пары к USDT в статусе TRADING, спот-разрешённые, без исключённых. */
    suspend fun usdtUniverse(excludedStables: Set<String>): List<SymbolInfo> =
        withContext(Dispatchers.IO) {
            val body = get("/api/v3/exchangeInfo")
            val info = json.decodeFromString(ExchangeInfoDto.serializer(), body)
            info.symbols.asSequence()
                .filter { it.quoteAsset == "USDT" }
                .filter { it.status == "TRADING" }
                .filter { it.isSpotTradingAllowed }
                .filter { it.baseAsset !in excludedStables }
                .filter { s ->
                    LEVERAGED_SUFFIXES.none { s.symbol.endsWith(it) }
                }
                .map { SymbolInfo(it.symbol, it.baseAsset, it.quoteAsset) }
                .toList()
        }

    /** Смещение часов устройства относительно сервера, мс (ТЗ раздел 29). */
    suspend fun clockSkewMillis(): Long = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis()
        val body = get("/api/v3/time")
        val server = json.decodeFromString(ServerTimeDto.serializer(), body).serverTime
        val after = System.currentTimeMillis()
        server - (before + after) / 2
    }

    private fun get(path: String): String {
        try {
            return call("$base$path")
        } catch (e: Exception) {
            return call("$fallback$path")
        }
    }

    private fun call(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw IllegalStateException("Empty body")
        }
    }

    companion object {
        private val LEVERAGED_SUFFIXES = listOf("UPUSDT", "DOWNUSDT", "BULLUSDT", "BEARUSDT")
        val DEFAULT_EXCLUDED = setOf(
            "USDC", "FDUSD", "TUSD", "USDP", "DAI", "EUR", "TRY", "BRL",
            "BUSD", "AEUR", "USD1", "USDE"
        )
    }
}
