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

    // Marks barely change within a session; cache per key so re-entering an episode
    // (or every episode of a season hitting the same season key) skips the network.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    private fun key(tmdbId: Int, season: Int?, episode: Int?) =
        "${tmdbId}_${season ?: 0}_${episode ?: 0}"

    /**
     * (introEndMs, creditsStartMs) — medians of community marks, 0 where none.
     * A serial episode without its own marks falls back to the season-wide key
     * (episode 0): one mark on any episode covers the whole season.
     */
    fun fetchMarks(tmdbId: Int, season: Int?, episode: Int?): Pair<Long, Long> {
        if (!enabled) return 0L to 0L
        val (e1, c1) = fetchKey(key(tmdbId, season, episode))
        if ((e1 > 0 && c1 > 0) || season == null || (episode ?: 0) == 0) return e1 to c1
        val (e2, c2) = fetchKey(key(tmdbId, season, 0))
        return (if (e1 > 0) e1 else e2) to (if (c1 > 0) c1 else c2)
    }

    private fun fetchKey(k: String): Pair<Long, Long> {
        cache[k]?.let { return it }
        // null = network/parse failure (don't cache — retry later); Pair = real answer.
        val res = fetchKeyNet(k) ?: return 0L to 0L
        cache[k] = res
        return res
    }

    private fun fetchKeyNet(k: String): Pair<Long, Long>? = runCatching {
        val body = httpGet("$DB_URL/intros/$k.json") ?: return null
        val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: return 0L to 0L   // valid "no marks" (null/empty node) → cacheable
        val intro = ArrayList<Long>(); val credits = ArrayList<Long>()
        obj.entrySet().forEach { e ->
            val o = e.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            o.get("e")?.takeIf { !it.isJsonNull }?.asLong?.let { if (it in 1_000L..600_000L) intro.add(it) }
            o.get("c")?.takeIf { !it.isJsonNull }?.asLong?.let { if (it in 1_000L..21_600_000L) credits.add(it) }
        }
        median(intro) to median(credits)
    }.getOrNull()

    /** Append a mark. [field] is "e" (intro end) or "c" (credits start).
     *  Serial episodes also feed the season-wide key so the rest of the season benefits. */
    fun submitMark(tmdbId: Int, season: Int?, episode: Int?, field: String, ms: Long) {
        if (!enabled || ms < 1_000L) return
        val epKey = key(tmdbId, season, episode)
        runCatching { httpPost("$DB_URL/intros/$epKey.json", """{"$field":$ms}""") }
        cache.remove(epKey)   // our own mark should be visible on the next fetch
        if (season != null && (episode ?: 0) != 0) {
            val seasonKey = key(tmdbId, season, 0)
            runCatching { httpPost("$DB_URL/intros/$seasonKey.json", """{"$field":$ms}""") }
            cache.remove(seasonKey)
        }
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
