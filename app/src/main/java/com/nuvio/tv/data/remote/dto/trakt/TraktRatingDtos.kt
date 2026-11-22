package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** One title in a Trakt ratings request: which title, and the score when setting one. */
@JsonClass(generateAdapter = true)
data class TraktRatingItemDto(
    @Json(name = "ids") val ids: TraktIdsDto,
    @Json(name = "rating") val rating: Int? = null
)

/** Trakt's bulk rating body; movies and shows are sent separately by type. */
@JsonClass(generateAdapter = true)
data class TraktRatingsRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingItemDto> = emptyList(),
    @Json(name = "shows") val shows: List<TraktRatingItemDto> = emptyList()
)
