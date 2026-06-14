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

    @GET("tv/{id}/season/{season}")
    suspend fun getSeason(
        @Path("id") id: Int,
        @Path("season") season: Int,
        @Query("language") language: String = "ru-RU",
        @Query("api_key") apiKey: String = TmdbRepository.API_KEY,
    ): TmdbSeason
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
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
)

data class TmdbGenre(val id: Int = 0, val name: String? = null)

data class TmdbCredits(
    val cast: List<TmdbPerson> = emptyList(),
    val crew: List<TmdbPerson> = emptyList(),
)

data class TmdbPerson(
    val name: String? = null,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    val profile_path: String? = null,
)

data class TmdbSeason(
    val season_number: Int = 0,
    val episodes: List<TmdbEpisode> = emptyList(),
)

data class TmdbEpisode(
    val episode_number: Int = 0,
    val season_number: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null,
    val runtime: Int? = null,
    val vote_average: Float? = null,
)
