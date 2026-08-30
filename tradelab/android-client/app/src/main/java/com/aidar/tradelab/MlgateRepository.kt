package com.aidar.tradelab

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

data class MlgateEvent(
    val id: Long,
    val tsMs: Long,
    val symbol: String,
    val eventType: String,
    val decision: String,
    val side: String,
    val pTail: Double?,
    val expectedReturn: Double?,
    val mlScore: Double?,
    val threshold: Double?,
    val modelVersion: String?,
)

data class MlgateStats(
    val accepts: Int,
    val vetos: Int,
    val acceptanceRate: Double?,
    val avgPTail: Double?,
    val avgExpectedReturn: Double?,
    val avgMlScore: Double?,
)

data class MlgateSnapshot(
    val stats: MlgateStats?,
    val events: List<MlgateEvent>,
    val error: String? = null,
)

class MlgateRepository(private val baseUrl: String, private val token: String) {

    fun fetchSnapshot(limit: Int = 10): MlgateSnapshot {
        val statsResult = get("/api/v1/mlgate/stats")
        if (statsResult.error != null) {
            return MlgateSnapshot(null, emptyList(), statsResult.error)
        }

        val stats = parseStats(statsResult.body)
            ?: return MlgateSnapshot(null, emptyList(), "ML gate: сервер вернул некорректную статистику")

        val eventsResult = get("/api/v1/mlgate/events?limit=${limit.coerceIn(1, 100)}")
        if (eventsResult.error != null) {
            return MlgateSnapshot(stats, emptyList(), eventsResult.error)
        }

        return MlgateSnapshot(
            stats = stats,
            events = parseEvents(eventsResult.body),
            error = null,
        )
    }

    private fun parseEvents(body: String): List<MlgateEvent> {
        val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("events") ?: JSONArray()
            else -> JSONArray()
        }

        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val payload = when (val raw = obj.opt("payload")) {
                    is JSONObject -> raw
                    is String -> raw.toJsonObjectOrNull()
                    else -> obj.optString("payload_json", "").toJsonObjectOrNull()
                }

                val eventType = obj.optString("event_type", "")
                val decision = firstString(
                    payload?.optString("decision", ""),
                    obj.optString("decision", ""),
                    inferDecision(eventType),
                )

                add(
                    MlgateEvent(
                        id = obj.optLong("id", 0L),
                        tsMs = firstLong(obj, "ts_ms", "timestamp_ms", "created_at_ms"),
                        symbol = firstString(
                            obj.optString("symbol", ""),
                            payload?.optString("symbol", ""),
                            "?",
                        ),
                        eventType = eventType,
                        decision = decision,
                        side = firstString(
                            payload?.optString("side", ""),
                            obj.optString("side", ""),
                        ),
                        pTail = firstDouble(payload, obj, "p_tail"),
                        expectedReturn = firstDouble(
                            payload,
                            obj,
                            "expected_return_pct",
                            "expected_return",
                        ),
                        mlScore = firstDouble(payload, obj, "ml_score", "score"),
                        threshold = firstDouble(payload, obj, "threshold"),
                        modelVersion = firstString(
                            payload?.optString("model_version", ""),
                            obj.optString("model_version", ""),
                        ).ifBlank { null },
                    )
                )
            }
        }
    }

    private fun parseStats(body: String): MlgateStats? {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return MlgateStats(
            accepts = firstInt(obj, "total_accepts", "accepts"),
            vetos = firstInt(obj, "total_vetos", "vetos"),
            acceptanceRate = firstDouble(obj, "acceptance_rate", "accept_rate"),
            avgPTail = firstDouble(obj, "avg_p_tail"),
            avgExpectedReturn = firstDouble(obj, "avg_expected_return", "avg_expected_return_pct"),
            avgMlScore = firstDouble(obj, "avg_ml_score", "avg_score"),
        )
    }

    private fun get(path: String): HttpResult {
        val connection = open("$baseUrl$path")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                HttpResult(code, body, httpError(code, body))
            } else {
                HttpResult(code, body, null)
            }
        } catch (e: Exception) {
            HttpResult(0, "", "ML gate: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            if (token.isNotEmpty()) setRequestProperty("X-TradeLab-Token", token)
            setRequestProperty("Accept", "application/json")
        }

    private fun httpError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optString("detail", "") }.getOrDefault("")
        return when (code) {
            401, 403 -> "ML gate: ошибка авторизации ($code)"
            404 -> "ML gate API не найден на сервере (404)"
            else -> if (detail.isNotBlank()) "ML gate: HTTP $code — $detail" else "ML gate: HTTP $code"
        }
    }

    private fun inferDecision(eventType: String): String = when {
        eventType.contains("ACCEPT", ignoreCase = true) -> "ACCEPT"
        eventType.contains("VETO", ignoreCase = true) || eventType.contains("BLOCK", ignoreCase = true) -> "VETO"
        else -> ""
    }

    private fun String.toJsonObjectOrNull(): JSONObject? =
        if (isBlank()) null else runCatching { JSONObject(this) }.getOrNull()

    private fun firstString(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private fun firstLong(obj: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            raw.toString().toLongOrNull()?.let { return it }
        }
        return 0L
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            raw.toString().toDoubleOrNull()?.toInt()?.let { return it }
        }
        return 0
    }

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            raw.toString().toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun firstDouble(payload: JSONObject?, top: JSONObject, vararg keys: String): Double? {
        if (payload != null) {
            firstDouble(payload, *keys)?.let { return it }
        }
        return firstDouble(top, *keys)
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
        val error: String?,
    )
}
