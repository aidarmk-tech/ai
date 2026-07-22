package com.binancetrader.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

data class SymbolRules(
    val stepSize: BigDecimal,
    val minQty: BigDecimal,
    val minNotional: BigDecimal,
    val baseAsset: String,
    val quoteAsset: String
)

data class OrderResult(
    val executedQty: Double,
    val quoteSpent: Double,
    val avgPrice: Double
)

class BinanceException(val code: Int, msg: String) : IOException("Binance $code: $msg")

/**
 * Минимальный REST-клиент Binance Spot API (боевой сервер или тестнет).
 * Подпись запросов — HMAC-SHA256, со сдвигом на серверное время.
 */
class BinanceClient(
    private val apiKey: String,
    private val apiSecret: String,
    testnet: Boolean
) {
    private val baseUrl = if (testnet) "https://testnet.binance.vision" else "https://api.binance.com"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var timeOffset = 0L

    private fun raw(path: String, params: String = ""): String {
        val url = baseUrl + path + if (params.isNotEmpty()) "?$params" else ""
        val req = Request.Builder().url(url).header("X-MBX-APIKEY", apiKey).build()
        return execute(req)
    }

    private fun signed(method: String, path: String, params: String): String {
        val ts = System.currentTimeMillis() + timeOffset
        val query = (if (params.isNotEmpty()) "$params&" else "") + "recvWindow=10000&timestamp=$ts"
        val signature = hmacSha256(query, apiSecret)
        val full = "$query&signature=$signature"
        val builder = Request.Builder().header("X-MBX-APIKEY", apiKey)
        val req = when (method) {
            "GET" -> builder.url("$baseUrl$path?$full").get().build()
            "POST" -> builder.url("$baseUrl$path")
                .post(full.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            else -> throw IllegalArgumentException(method)
        }
        return execute(req)
    }

    private fun execute(req: Request): String {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                var code = resp.code
                var msg = body
                try {
                    val j = JSONObject(body)
                    code = j.optInt("code", code)
                    msg = j.optString("msg", body)
                } catch (_: Exception) {
                }
                throw BinanceException(code, msg)
            }
            return body
        }
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Синхронизация часов с сервером — иначе подписанные запросы отклоняются (-1021). */
    fun syncTime() {
        val j = JSONObject(raw("/api/v3/time"))
        timeOffset = j.getLong("serverTime") - System.currentTimeMillis()
    }

    fun ping(): Boolean = try {
        raw("/api/v3/ping"); true
    } catch (e: Exception) {
        false
    }

    fun klines(symbol: String, interval: String, limit: Int): List<Candle> {
        val arr = JSONArray(raw("/api/v3/klines", "symbol=$symbol&interval=$interval&limit=$limit"))
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.getJSONArray(i)
            out.add(
                Candle(
                    openTime = k.getLong(0),
                    open = k.getString(1).toDouble(),
                    high = k.getString(2).toDouble(),
                    low = k.getString(3).toDouble(),
                    close = k.getString(4).toDouble(),
                    volume = k.getString(5).toDouble(),
                    closeTime = k.getLong(6)
                )
            )
        }
        return out
    }

    fun lastPrice(symbol: String): Double {
        val j = JSONObject(raw("/api/v3/ticker/price", "symbol=$symbol"))
        return j.getString("price").toDouble()
    }

    fun symbolRules(symbol: String): SymbolRules {
        val j = JSONObject(raw("/api/v3/exchangeInfo", "symbol=$symbol"))
        val info = j.getJSONArray("symbols").getJSONObject(0)
        val filters = info.getJSONArray("filters")
        var step = BigDecimal("0.00000001")
        var minQty = BigDecimal.ZERO
        var minNotional = BigDecimal.ZERO
        for (i in 0 until filters.length()) {
            val f = filters.getJSONObject(i)
            when (f.getString("filterType")) {
                "LOT_SIZE" -> {
                    step = BigDecimal(f.getString("stepSize"))
                    minQty = BigDecimal(f.getString("minQty"))
                }
                "NOTIONAL" -> minNotional = BigDecimal(f.optString("minNotional", "0"))
                "MIN_NOTIONAL" -> minNotional = BigDecimal(f.optString("minNotional", "0"))
            }
        }
        return SymbolRules(
            stepSize = step,
            minQty = minQty,
            minNotional = minNotional,
            baseAsset = info.getString("baseAsset"),
            quoteAsset = info.getString("quoteAsset")
        )
    }

    fun freeBalance(asset: String): Double {
        val j = JSONObject(signed("GET", "/api/v3/account", "omitZeroBalances=true"))
        val balances = j.getJSONArray("balances")
        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            if (b.getString("asset") == asset) return b.getString("free").toDouble()
        }
        return 0.0
    }

    /** Рыночная покупка на сумму quoteQty (в котируемой валюте, например USDT). */
    fun marketBuy(symbol: String, quoteQty: Double): OrderResult {
        val qq = BigDecimal(quoteQty).setScale(2, RoundingMode.DOWN).toPlainString()
        val body = signed(
            "POST", "/api/v3/order",
            "symbol=$symbol&side=BUY&type=MARKET&quoteOrderQty=$qq&newOrderRespType=FULL"
        )
        return parseFill(JSONObject(body))
    }

    /** Рыночная продажа количества qty (округляется вниз до stepSize снаружи). */
    fun marketSell(symbol: String, qty: BigDecimal): OrderResult {
        val body = signed(
            "POST", "/api/v3/order",
            "symbol=$symbol&side=SELL&type=MARKET&quantity=${qty.toPlainString()}&newOrderRespType=FULL"
        )
        return parseFill(JSONObject(body))
    }

    private fun parseFill(j: JSONObject): OrderResult {
        val executed = j.optString("executedQty", "0").toDouble()
        val quote = j.optString("cummulativeQuoteQty", "0").toDouble()
        val avg = if (executed > 0) quote / executed else 0.0
        return OrderResult(executed, quote, avg)
    }

    companion object {
        fun roundToStep(qty: Double, step: BigDecimal): BigDecimal {
            if (step.compareTo(BigDecimal.ZERO) == 0) return BigDecimal(qty)
            val q = BigDecimal(qty)
            val steps = q.divide(step, 0, RoundingMode.DOWN)
            return steps.multiply(step).stripTrailingZeros()
        }
    }
}
