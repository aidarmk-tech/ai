package com.aidar.tradelab

import org.json.JSONObject
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

class MlgateRepository(private val baseUrl: String, private val token: String) {

    fun fetchEvents(limit: Int = 50): List<MlgateEvent> {
        val c = open("$baseUrl/api/v1/mlgate/events?limit=$limit")
        return try {
            val code = c.responseCode
            if (code !in 200..299) return emptyList()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(body).optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val payload = obj.optJSONObject("payload")
                    add(MlgateEvent(
                        id = obj.getLong("id"),
                        tsMs = obj.getLong("ts_ms"),
                        symbol = obj.optString("symbol", "?"),
                        eventType = obj.optString("event_type", ""),
                        decision = payload?.optString("decision", "") ?: "",
                        side = payload?.optString("side", "") ?: "",
                        pTail = payload?.opt("p_tail")?.toString()?.toDoubleOrNull(),
                        expectedReturn = payload?.opt("expected_return_pct")?.toString()?.toDoubleOrNull(),
                        mlScore = payload?.opt("ml_score")?.toString()?.toDoubleOrNull(),
                        threshold = payload?.opt("threshold")?.toString()?.toDoubleOrNull(),
                        modelVersion = payload?.optString("model_version", ""),
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            c.disconnect()
        }
    }

    fun fetchStats(): MlgateStats? {
        val c = open("$baseUrl/api/v1/mlgate/stats")
        return try {
            val code = c.responseCode
            if (code !in 200..299) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            MlgateStats(
                accepts = obj.optInt("total_accepts", 0),
                vetos = obj.optInt("total_vetos", 0),
                acceptanceRate = obj.opt("acceptance_rate")?.toString()?.toDoubleOrNull(),
                avgPTail = obj.opt("avg_p_tail")?.toString()?.toDoubleOrNull(),
                avgExpectedReturn = obj.opt("avg_expected_return")?.toString()?.toDoubleOrNull(),
                avgMlScore = obj.opt("avg_ml_score")?.toString()?.toDoubleOrNull(),
            )
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            if (token.isNotEmpty()) setRequestProperty("X-TradeLab-Token", token)
            setRequestProperty("Accept", "application/json")
        }
}
