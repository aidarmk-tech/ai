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

    companion object {
        // Set your TMDB API key here or via BuildConfig
        const val API_KEY = "a0ce3eb86e4197432bac852601427019"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"
        fun posterUrl(path: String?) = path?.let { "${IMAGE_BASE}w500$it" }
        fun backdropUrl(path: String?) = path?.let { "${IMAGE_BASE}w1280$it" }
    }
}
