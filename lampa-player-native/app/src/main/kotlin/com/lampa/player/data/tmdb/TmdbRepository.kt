package com.lampa.player.data.tmdb

import com.google.gson.Gson
import com.lampa.player.data.db.MetadataDao
import com.lampa.player.data.db.MetadataEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

@Singleton
class TmdbRepository @Inject constructor(
    private val api: TmdbApi,
    private val dao: MetadataDao,
) {
    private val gson = Gson()

    suspend fun getMetadata(tmdbId: Int, isSerial: Boolean): TmdbMetadata? {
        val type = if (isSerial) "tv" else "movie"

        val cached = dao.get(tmdbId, type)
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < CACHE_TTL_MS) {
            return runCatching { gson.fromJson(cached.json, TmdbMetadata::class.java) }.getOrNull()
        }

        dao.deleteExpired(System.currentTimeMillis() - CACHE_TTL_MS)

        return runCatching {
            val meta = if (isSerial) api.getTv(tmdbId) else api.getMovie(tmdbId)
            dao.insert(MetadataEntity(tmdbId = tmdbId, type = type, json = gson.toJson(meta)))
            meta
        }.getOrNull()
    }

    /** Поиск по названию + загрузка полных данных. Используется когда tmdb_id не был передан из Lampa. */
    suspend fun searchAndGet(query: String, isSerial: Boolean): TmdbMetadata? {
        return runCatching {
            val results = api.searchMulti(query).results
            val best = results.firstOrNull {
                if (isSerial) it.media_type == "tv" else it.media_type == "movie"
            } ?: results.firstOrNull { it.media_type == "movie" || it.media_type == "tv" }
            best?.let { getMetadata(it.id, it.media_type == "tv") }
        }.getOrNull()
    }

    companion object {
        const val API_KEY = "a0ce3eb86e4197432bac852601427019"
        const val BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhMGNlM2ViODZlNDE5NzQzMmJhYzg1MjYwMTQyNzAxOSIsIm5iZiI6MTc3ODk2MzIyOC43ODksInN1YiI6IjZhMDhkMzFjMzhmMGUyNWI4YTY1OTAzMCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.num2SkSlX5a92YYUlKv_NqZSqL6EKK1AVNQrJs8WuJ8"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        fun posterUrl(path: String?) = path?.let { "${IMAGE_BASE}w500$it" }
        fun backdropUrl(path: String?) = path?.let { "${IMAGE_BASE}w1280$it" }
    }
}
