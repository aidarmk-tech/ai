package com.lampplayer.tv.player

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crowdsourced intro-timecode store on Firebase Realtime Database (plain REST,
 * no SDK). Key = "<tmdbId>_<season>_<episode>"; each user appends their mark,
 * and we read back the MEDIAN so a single bad mark can't poison the result.
 * Disabled until DB_URL is set (then it's a one-line activation).
 */
object IntroDb {
    // e.g. "https://lampplayer-xxxx-default-rtdb.firebaseio.com"
    private const val DB_URL = ""

    val enabled: Boolean get() = DB_URL.isNotBlank()

    private fun key(tmdbId: Int, season: Int?, episode: Int?) =
        "${tmdbId}_${season ?: 0}_${episode ?: 0}"

    /** Median intro-end (ms) others reported for this episode, or 0 if none/disabled. */
    fun fetchIntroEndMs(tmdbId: Int, season: Int?, episode: Int?): Long {
        if (!enabled) return 0L
        return runCatching {
            val body = httpGet("$DB_URL/intros/${key(tmdbId, season, episode)}.json") ?: return 0L
            val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return 0L
            val vals = obj.entrySet().mapNotNull {
                it.value?.takeIf { v -> v.isJsonObject }?.asJsonObject
                    ?.get("e")?.takeIf { e -> !e.isJsonNull }?.asLong
            }.filter { it in 1_000L..600_000L }.sorted()
            if (vals.isEmpty()) 0L else vals[vals.size / 2]   // median
        }.getOrDefault(0L)
    }

    /** Append this user's mark for the episode. */
    fun submitIntroEndMs(tmdbId: Int, season: Int?, episode: Int?, introEndMs: Long) {
        if (!enabled || introEndMs !in 1_000L..600_000L) return
        runCatching {
            httpPost("$DB_URL/intros/${key(tmdbId, season, episode)}.json", """{"e":$introEndMs}""")
        }
    }

    private fun httpGet(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
        }
        return if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
               else { c.disconnect(); null }
    }

    private fun httpPost(url: String, json: String) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        c.responseCode; c.disconnect()
    }
}
