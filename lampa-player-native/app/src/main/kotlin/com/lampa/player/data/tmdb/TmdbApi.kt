package com.lampa.player.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("movie/{id}")
    suspend fun getMovie(
        @Path("id") id: Int,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "credits",
    ): TmdbMetadata

    @GET("tv/{id}")
    suspend fun getTv(
        @Path("id") id: Int,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "credits",
    ): TmdbMetadata

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String = "ru-RU",
    ): TmdbSearchResponse
}

data class TmdbMetadata(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Float? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
)

data class TmdbSearchResponse(
    val results: List<TmdbSearchItem> = emptyList(),
)

data class TmdbSearchItem(
    val id: Int = 0,
    val media_type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val vote_average: Float? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
)
