package com.lampplayer.tv.player

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Crowdsourced intro/credits-timecode store on Firebase Realtime Database (plain
 * REST, no SDK). Key = "<tmdbId>_<season>_<episode>"; each user appends a mark
 * carrying "e" (intro END, ms) and/or "c" (credits START, ms). We read back the
 * MEDIAN of each so a single bad mark can't poison the result.
 */
object IntroDb {
    private const val DB_URL = "https://introskip-cd974-default-rtdb.europe-west1.firebasedatabase.app"

    val enabled: Boolean get() = DB_URL.isNotBlank()

    private fun key(tmdbId: Int, season: Int?, episode: Int?) =
        "${tmdbId}_${season ?: 0}_${episode ?: 0}"

    /** (introEndMs, creditsStartMs) — medians of community marks, 0 where none. */
    fun fetchMarks(tmdbId: Int, season: Int?, episode: Int?): Pair<Long, Long> {
        if (!enabled) return 0L to 0L
        return runCatching {
            val body = httpGet("$DB_URL/intros/${key(tmdbId, season, episode)}.json") ?: return 0L to 0L
            val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return 0L to 0L
            val intro = ArrayList<Long>(); val credits = ArrayList<Long>()
            obj.entrySet().forEach { e ->
                val o = e.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                o.get("e")?.takeIf { !it.isJsonNull }?.asLong?.let { if (it in 1_000L..600_000L) intro.add(it) }
                o.get("c")?.takeIf { !it.isJsonNull }?.asLong?.let { if (it in 1_000L..21_600_000L) credits.add(it) }
            }
            median(intro) to median(credits)
        }.getOrDefault(0L to 0L)
    }

    /** Append a mark. [field] is "e" (intro end) or "c" (credits start). */
    fun submitMark(tmdbId: Int, season: Int?, episode: Int?, field: String, ms: Long) {
        if (!enabled || ms < 1_000L) return
        runCatching { httpPost("$DB_URL/intros/${key(tmdbId, season, episode)}.json", """{"$field":$ms}""") }
    }

    private fun median(xs: List<Long>): Long = if (xs.isEmpty()) 0L else xs.sorted()[xs.size / 2]

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
