package com.nuvio.tv.data.tracking

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.mdblist.MdbListApiClient
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.simkl.SimklApiClient
import com.nuvio.tv.data.simkl.SimklApiRequest
import com.nuvio.tv.data.simkl.SimklHttpMethod
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sends the user's own rating for a title (1-10) to every signed-in tracking service, or clears it.
 *
 * Each service is asked independently and answers independently: one being down or unhappy does not
 * stop the others, and the caller gets one [Outcome] per service so it can say what worked.
 */
@Singleton
class PersonalRatingsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val simklClient: SimklApiClient,
    private val mdbListClient: MdbListApiClient,
    private val providers: TrackingProviderRegistry
) {
    data class Outcome(
        val provider: TrackingProviderId,
        val ok: Boolean,
        val message: String? = null
    )

    /** The signed-in services that accept a rating. */
    fun connected(): List<TrackingProviderId> =
        providers.connectedProviderIds().filter { it in SUPPORTED }.sortedBy { it.ordinal }

    /**
     * Sets [rating] (1..10) on every connected service, or removes it when null.
     * A [rating] outside 1..10 is ignored by the services, so it is rejected here.
     */
    suspend fun submit(media: TrackingMediaReference, rating: Int?): List<Outcome> {
        if (rating != null && rating !in 1..10) {
            return connected().map { Outcome(it, ok = false, message = "Rating must be between 1 and 10") }
        }
        return coroutineScope {
            connected().map { provider ->
                async {
                    runCatching { send(provider, media, rating) }
                        .fold(
                            onSuccess = { Outcome(provider, ok = true) },
                            onFailure = { Outcome(provider, ok = false, message = it.message ?: "Failed") }
                        )
                }
            }.awaitAll()
        }
    }

    private suspend fun send(provider: TrackingProviderId, media: TrackingMediaReference, rating: Int?) =
        when (provider) {
            TrackingProviderId.TRAKT -> sendTrakt(media, rating)
            TrackingProviderId.SIMKL -> sendSimkl(media, rating)
            TrackingProviderId.MDBLIST -> sendMdbList(media, rating)
        }

    private suspend fun sendTrakt(media: TrackingMediaReference, rating: Int?) {
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
        val response = traktAuthService.executeAuthorizedRequest { header ->
            if (rating == null) traktApi.removeRatings(header, body) else traktApi.addRatings(header, body)
        } ?: throw IllegalStateException("Trakt is not signed in")
        if (!response.isSuccessful) throw IllegalStateException("Trakt: HTTP ${response.code()}")
    }

    private suspend fun sendSimkl(media: TrackingMediaReference, rating: Int?) {
        val key = when (media.kind) {
            TrackingMediaKind.MOVIE -> "movies"
            TrackingMediaKind.ANIME -> "anime"
            TrackingMediaKind.SHOW -> "shows"
        }
        val body = buildJsonObject {
            putJsonArray(key) {
                add(buildJsonObject {
                    putJsonObject("ids") {
                        media.ids.imdb?.let { put("imdb", it) }
                        media.ids.tmdb?.let { put("tmdb", it.toString()) }
                        media.ids.tvdb?.let { put("tvdb", it.toString()) }
                        media.ids.simkl?.let { put("simkl", it) }
                        media.ids.mal?.let { put("mal", it.toString()) }
                    }
                    rating?.let { put("rating", it) }
                })
            }
        }.toString()
        val path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        val response = simklClient.execute(
            SimklApiRequest(method = SimklHttpMethod.POST, path = path, body = body)
        )
        if (response.status !in 200..299) throw IllegalStateException("Simkl: HTTP ${response.status}")
    }

    private suspend fun sendMdbList(media: TrackingMediaReference, rating: Int?) {
        val key = if (media.kind == TrackingMediaKind.MOVIE) "movies" else "shows"
        val body = buildJsonObject {
            putJsonArray(key) {
                add(buildJsonObject {
                    putJsonObject("ids") {
                        media.ids.imdb?.let { put("imdb", it) }
                        media.ids.tmdb?.let { put("tmdb", it) }
                        media.ids.trakt?.let { put("trakt", it) }
                    }
                    rating?.let { put("rating", it) }
                })
            }
        }.toString()
        val path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        val response = mdbListClient.post(path, body)
        if (response.status !in 200..299) throw IllegalStateException("MDBList: HTTP ${response.status}")
    }

    private companion object {
        val SUPPORTED = setOf(
            TrackingProviderId.TRAKT,
            TrackingProviderId.SIMKL,
            TrackingProviderId.MDBLIST
        )
    }
}
