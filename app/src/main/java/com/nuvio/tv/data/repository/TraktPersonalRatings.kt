package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatingProvider
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Trakt's personal-rating source used by the detail-page rating action. */
@Singleton
class TraktPersonalRatings @Inject constructor(
    private val api: TraktApi,
    private val auth: TraktAuthService,
    private val profiles: ProfileManager
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.TRAKT
    private val revision = MutableStateFlow(0L)
    override val changes: Flow<Any?> = revision
    private val ratings = ConcurrentHashMap<String, Double>()

    override fun supports(media: TrackingMediaReference): Boolean =
        media.kind != TrackingMediaKind.ANIME && media.ids.hasAny

    override fun rating(media: TrackingMediaReference): Double? = ratings[cacheKey(media)]

    override suspend fun rate(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?
    ): TrackingMutationResult {
        if (profileId != profiles.activeProfileId.value) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        submit(media, rating)
        remember(media, rating)
        return TrackingMutationResult(attemptedCount = 1)
    }

    suspend fun submit(media: TrackingMediaReference, rating: Int?) {
        require(rating == null || rating in 1..10)
        val ids = TraktIdsDto(
            imdb = media.ids.imdb,
            tmdb = media.ids.tmdb?.toInt(),
            trakt = media.ids.trakt?.toInt(),
            tvdb = media.ids.tvdb?.toIntOrNull()
        )
        val item = TraktRatingItemDto(ids = ids, rating = rating)
        val body = if (media.kind == TrackingMediaKind.MOVIE) {
            TraktRatingsRequestDto(movies = listOf(item))
        } else {
            TraktRatingsRequestDto(shows = listOf(item))
        }
        val response = auth.executeAuthorizedRequest { header ->
            if (rating == null) api.removeRatings(header, body) else api.addRatings(header, body)
        } ?: throw IllegalStateException("Trakt is not signed in")
        if (!response.isSuccessful) throw IllegalStateException("Trakt: HTTP ${response.code()}")
    }

    fun remember(media: TrackingMediaReference, rating: Int?) {
        val key = cacheKey(media)
        if (rating == null) ratings.remove(key) else ratings[key] = rating.toDouble()
        revision.value++
    }

    private fun cacheKey(media: TrackingMediaReference): String =
        "${profiles.activeProfileId.value}:${media.stableKey}"
}
