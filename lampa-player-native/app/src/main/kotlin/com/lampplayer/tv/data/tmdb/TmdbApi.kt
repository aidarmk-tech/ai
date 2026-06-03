package com.lampplayer.tv.data.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("movie/{id}")
    suspend fun getMovie(
        @Path("id") id: Int,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "credits,videos",
        @Query("api_key") apiKey: String = TmdbRepository.API_KEY,
    ): TmdbMetadata

    @GET("tv/{id}")
    suspend fun getTv(
        @Path("id") id: Int,
        @Query("language") language: String = "ru-RU",
        @Query("append_to_response") appendToResponse: String = "credits,videos",
        @Query("api_key") apiKey: String = TmdbRepository.API_KEY,
    ): TmdbMetadata
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
