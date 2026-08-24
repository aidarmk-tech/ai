package com.aidar.tradelab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiException(val httpCode: Int, message: String) : IOException(message)

data class HealthInfo(
    val ok: Boolean,
    val version: String,
    val marketEnabled: Boolean,
    val lastSampleMs: Long,
    val snapshotIntervalHours: Int,
    val epochId: String,
    val epochStartedAtMs: Long,
)

data class StatRow(
    val participantId: String,
    val displayName: String?,
    val equity: Double,
    val rank: Int?,
    val role: String?,
    val trades: Int,
    val closedTrades: Int,
    val openTrades: Int,
    val netPnlUsdt: Double,
    val meanNetReturnPct: Double?,
    val winners: Int,
    val profitFactor: Double?,
)

data class Tournament(
    val epochId: String,
    val epochStartedAtMs: Long,
    val participants: List<StatRow>,
)

data class ComponentHealth(val component: String, val status: String, val detail: String?)

data class MarketStatus(
    val recorderEnabled: Boolean?,
    val components: List<ComponentHealth>,
)

class ApiClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val base: String
        get() = (prefs.getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL)
            .trim().trimEnd('/')
    private val token: String
        get() = prefs.getString("read_token", "")?.trim().orEmpty()

    fun health(): HealthInfo {
        val j = get("/health")
        return HealthInfo(
            ok = j.optBoolean("ok", false),
            version = j.optString("version", "?"),
            marketEnabled = j.optBoolean("market_enabled", false),
            lastSampleMs = j.optLong("last_sample_ms", 0L),
            snapshotIntervalHours = j.optInt("snapshot_interval_hours", 4),
            epochId = j.optString("research_epoch_id", ""),
            epochStartedAtMs = j.optLong("research_epoch_started_at_ms", 0L),
        )
    }

    fun tournament(): Tournament {
        val j = get("/api/v1/tournament")
        val epoch = j.optJSONObject("epoch") ?: JSONObject()
        val rows = mutableListOf<StatRow>()
        val arr = j.optJSONArray("participants")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                rows.add(parseStat(p))
            }
        }
        return Tournament(
            epochId = epoch.optString("epoch_id", ""),
            epochStartedAtMs = epoch.optLong("started_at_ms", 0L),
            participants = rows,
        )
    }

    fun marketStatus(): MarketStatus {
        val j = get("/api/v1/market/status")
        val rec = j.optJSONObject("recorder")
        val comps = mutableListOf<ComponentHealth>()
        val arr = j.optJSONArray("components")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                comps.add(
                    ComponentHealth(
                        component = c.optString("component", "?"),
                        status = c.optString("status", "?"),
                        detail = c.optString("detail", null),
                    )
                )
            }
        }
        return MarketStatus(recorderEnabled = rec?.optBoolean("enabled"), components = comps)
    }

    private fun parseStat(p: JSONObject): StatRow = StatRow(
        participantId = p.optString("participant_id", "?"),
        displayName = p.optString("display_name", null),
        equity = p.optDouble("equity"),
        rank = p.optIntOrNull("rank"),
        role = p.optString("role", null),
        trades = p.optInt("trades", 0),
        closedTrades = p.optInt("closed_trades", 0),
        openTrades = p.optInt("open_trades", 0),
        netPnlUsdt = p.optDouble("net_pnl_usdt"),
        meanNetReturnPct = p.optDoubleOrNull("mean_net_return_pct"),
        winners = p.optInt("winners", 0),
        profitFactor = p.optDoubleOrNull("profit_factor"),
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    @Suppress("NOTHING_TO_INLINE")
    private inline fun JSONObject.optDouble(key: String): Double = optDouble(key, Double.NaN)

    private fun get(path: String): JSONObject {
        val c = (java.net.URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (token.isNotEmpty()) setRequestProperty("X-TradeLab-Token", token)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            if (code == 401) throw ApiException(401, "bad read token")
            if (code !in 200..299) throw ApiException(code, "HTTP $code for $path")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: UnknownHostException) {
            throw IOException("Сервер недоступен (не найден адрес)")
        } catch (e: SocketTimeoutException) {
            throw IOException("Таймаут соединения")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun isWifiOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        fun isMetered(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}
