package com.nuvio.tv.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A Spotify account token pair. Never log these values. */
internal data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long
)

internal data class SpotifyProfile(
    val id: String,
    val displayName: String,
    val country: String?,
    val product: String?
)

internal data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val ownerName: String?,
    val trackCount: Int,
    val collaborative: Boolean
)

internal data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationMs: Long,
    val isLocal: Boolean
)

internal class SpotifyAuthException(message: String) : Exception(message)

/**
 * The small part of Spotify's Web API the Music section needs: the PKCE token exchange and the
 * account's playlists, Liked Songs and playlist tracks. The TV never sees a client secret.
 */
internal object MusicSpotifyApi {
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val API_URL = "https://api.spotify.com/v1"
    private const val MAX_PAGES = 200

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): SpotifyTokens? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        postToken(form)
    }

    suspend fun refresh(clientId: String, refreshToken: String): SpotifyTokens? =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build()
            postToken(form)
        }

    suspend fun profile(accessToken: String): SpotifyProfile? = withContext(Dispatchers.IO) {
        val json = getJson("$API_URL/me", accessToken) ?: return@withContext null
        SpotifyProfile(
            id = json.optString("id"),
            displayName = json.optString("display_name").ifBlank { json.optString("id") },
            country = json.optString("country").takeIf { it.isNotBlank() },
            product = json.optString("product").takeIf { it.isNotBlank() }
        )
    }

    suspend fun playlists(accessToken: String): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val result = ArrayList<SpotifyPlaylist>()
        var url: String? = "$API_URL/me/playlists?limit=50"
        var page = 0
        while (url != null && page < MAX_PAGES) {
            val json = getJson(url, accessToken) ?: break
            val items = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val tracks = item.optJSONObject("tracks")
                result += SpotifyPlaylist(
                    id = id,
                    name = item.optString("name").ifBlank { "Spotify playlist" },
                    ownerName = item.optJSONObject("owner")?.optString("display_name")
                        ?.takeIf { it.isNotBlank() },
                    trackCount = tracks?.optInt("total") ?: 0,
                    collaborative = item.optBoolean("collaborative")
                )
            }
            url = json.optString("next").takeIf { it.isNotBlank() }
            page++
        }
        result
    }

    suspend fun likedTracks(accessToken: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val result = ArrayList<SpotifyTrack>()
        var url: String? = "$API_URL/me/tracks?limit=50"
        var page = 0
        while (url != null && page < MAX_PAGES) {
            val json = getJson(url, accessToken) ?: break
            val items = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                parseTrack(entry.optJSONObject("track"))?.let(result::add)
            }
            url = json.optString("next").takeIf { it.isNotBlank() }
            page++
        }
        result
    }

    suspend fun playlistTracks(accessToken: String, playlistId: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<SpotifyTrack>()
            var url: String? = "$API_URL/playlists/$playlistId/tracks?limit=100"
            var page = 0
            while (url != null && page < MAX_PAGES) {
                val json = getJson(url, accessToken) ?: break
                val items = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val entry = items.optJSONObject(i) ?: continue
                    // A removed or unavailable item has track = null.
                    parseTrack(entry.optJSONObject("track"))?.let(result::add)
                }
                url = json.optString("next").takeIf { it.isNotBlank() }
                page++
            }
            result
        }

    private fun parseTrack(json: JSONObject?): SpotifyTrack? {
        if (json == null) return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("name").takeIf { it.isNotBlank() } ?: return null
        val artistsArray = json.optJSONArray("artists")
        val artists = buildList {
            if (artistsArray != null) {
                for (i in 0 until artistsArray.length()) {
                    artistsArray.optJSONObject(i)?.optString("name")
                        ?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.joinToString(", ")
        return SpotifyTrack(
            id = id,
            title = title,
            artists = artists,
            album = json.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() },
            durationMs = json.optLong("duration_ms"),
            isLocal = json.optBoolean("is_local")
        )
    }

    private fun postToken(form: FormBody): SpotifyTokens? {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
            return SpotifyTokens(
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
            )
        }
    }

    private fun getJson(url: String, accessToken: String): JSONObject? {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw SpotifyAuthException("Spotify sign-in expired.")
                if (response.code == 429 && attempt == 0) {
                    val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 10L) ?: 2L
                    Thread.sleep(wait * 1000L)
                    attempt++
                    return@use
                }
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                return runCatching { JSONObject(body) }.getOrNull()
            }
            // The 429 branch asked for one more attempt; loop around.
        }
    }
}
