package com.nuvio.tv.music.spotifyweb

import java.net.URLEncoder
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
internal data class SpotifyTrack(
    val name: String,
    val artists: List<String> = emptyList(),
    val local: Boolean = false,
    val art: String? = null,
    val durationMs: Long = 0L,
) {
    val artistLine: String get() = artists.joinToString(", ")
}

internal data class SpotifyPlaylist(
    val name: String,
    val tracks: List<SpotifyTrack>,
    val skipped: Int,
)

internal sealed interface SpotifySource {
    data class Playlist(val id: String) : SpotifySource
    data object Liked : SpotifySource

    val key: String
        get() = when (this) {
            is Playlist -> id
            Liked -> "liked"
        }

    companion object {
        fun parse(link: String): SpotifySource? {
            val value = link.trim()
            if (value.isBlank()) return null
            if (value.endsWith("/collection/tracks") || value.equals("liked", true) || value.equals("liked songs", true)) {
                return Liked
            }
            if (value.startsWith("spotify:playlist:")) {
                return value.removePrefix("spotify:playlist:").takeIf(::validId)?.let { Playlist(it) }
            }
            val id = runCatching {
                val uri = java.net.URI(value)
                if (uri.host !in setOf("open.spotify.com", "www.open.spotify.com")) return null
                val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() && !it.startsWith("intl-") }
                if (parts.size == 2 && parts[0] == "playlist") parts[1] else null
            }.getOrNull()
            if (id != null) return id.takeIf(::validId)?.let { Playlist(it) }
            return value.takeIf(::validId)?.let { Playlist(it) }
        }

        private fun validId(value: String): Boolean =
            value.length == 22 && value.all { it.isLetterOrDigit() }
    }
}

internal data class SpotifyEntry(
    val source: SpotifySource,
    val name: String,
    val owner: String,
    val count: Int?,
    val art: String?,
)

internal data class SpotifyAccount(
    val name: String,
    val library: List<SpotifyEntry>,
)

internal class SpotifyWebClient private constructor(
    private val accessToken: String,
    private val clientToken: String,
    private val clientVersion: String,
    private val hashes: Map<String, String>,
    private val signedIn: Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    val isSignedIn: Boolean get() = signedIn

    suspend fun account(): Result<SpotifyAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = runCatching { query("profileAttributes", JsonObject(emptyMap())) }.getOrNull()
            val name = profile?.obj("data")?.obj("me")?.obj("profile")?.string("name").orEmpty()
            val libraryResults = runCatching { library() }.getOrNull().orEmpty()
            val library = if (libraryResults.isNotEmpty()) libraryResults else webApiPlaylists()
            val entries = library.toMutableList()
            if (entries.none { it.source == SpotifySource.Liked }) {
                val count = runCatching {
                    query("fetchLibraryTracks", buildJsonObjectMap("offset" to 0, "limit" to 1))
                        .obj("data")?.obj("me")?.obj("library")?.obj("tracks")?.int("totalCount")
                }.getOrNull()
                entries.add(0, SpotifyEntry(SpotifySource.Liked, "Liked Songs", "", count, null))
            }
            SpotifyAccount(name, entries)
        }
    }

    suspend fun playlist(linkOrId: String, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Result<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = SpotifySource.parse(linkOrId) ?: error("That isn't a Spotify playlist link.")
                if (source is SpotifySource.Liked) error("Use Liked Songs for the collection/tracks link.")
                val id = (source as SpotifySource.Playlist).id
                val first = page(id, 0, PageSize)
                val name = first.string("name")?.trim()?.takeIf { it.isNotBlank() }
                    ?: error("Spotify returned no playlist name.")
                val total = first.obj("content")?.int("totalCount") ?: 0
                val items = first.obj("content")?.array("items")?.toMutableList() ?: mutableListOf<JsonObject>()
                onProgress(items.size, maxOf(total, items.size))
                val offsets = (PageSize..maxOf(total, items.size) step PageSize).toList()
                val pages: List<Pair<Int, List<JsonObject>>> = coroutineScope {
                    offsets.map { offset ->
                        async<Pair<Int, List<JsonObject>>> {
                            runCatching {
                                offset to (page(id, offset, PageSize).obj("content")?.array("items") ?: emptyList())
                            }.getOrDefault(offset to emptyList())
                        }
                    }.awaitAll()
                }
                var done = items.size
                pages.sortedBy { it.first }.forEach { (_, more) ->
                    items.addAll(more)
                    done += more.size
                    onProgress(done, maxOf(total, done))
                }
                toPlaylist(name, items)
            }
        }

    suspend fun liked(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Result<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!signedIn) error("Sign in to Spotify to copy your Liked Songs.")
                val first = libraryTracksPage(0)
                val items = first.array("items")?.toMutableList() ?: mutableListOf<JsonObject>()
                val total = maxOf(first.int("totalCount") ?: 0, items.size)
                onProgress(items.size, total)
                val offsets = (LibraryPage..total step LibraryPage).toList()
                val pages: List<Pair<Int, List<JsonObject>>> = coroutineScope {
                    offsets.map { offset ->
                        async<Pair<Int, List<JsonObject>>> {
                            runCatching { offset to (libraryTracksPage(offset).array("items") ?: emptyList()) }
                                .getOrDefault(offset to emptyList())
                        }
                    }.awaitAll()
                }
                var done = items.size
                pages.sortedBy { it.first }.forEach { (_, more) ->
                    items.addAll(more)
                    done += more.size
                    onProgress(done, total)
                }
                toPlaylist("Liked Songs", items)
            }
        }

    fun close() = Unit

    private fun page(id: String, offset: Int, limit: Int): JsonObject {
        val variables = buildJsonObjectMap(
            "uri" to "spotify:playlist:$id",
            "offset" to offset,
            "limit" to limit,
            "enableWatchFeedEntrypoint" to false,
        )
        val body = query("fetchPlaylist", variables)
        val playlist = body.obj("data")?.obj("playlistV2")
        if (playlist == null || playlist.string("__typename") == "NotFound") {
            error("Spotify couldn't find that playlist. If it's private, sign in to Spotify first.")
        }
        return playlist
    }

    private fun libraryTracksPage(offset: Int): JsonObject {
        val body = query("fetchLibraryTracks", buildJsonObjectMap("offset" to offset, "limit" to LibraryPage))
        return body.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
            ?: error("Spotify didn't return Liked Songs; sign in again.")
    }

    private suspend fun library(): List<SpotifyEntry> {
        val first = libraryPage(0)
        val total = first.int("totalCount") ?: 0
        val items = first.array("items")?.toMutableList() ?: mutableListOf<JsonObject>()
        if (total > LibraryPage) {
            val offsets = (LibraryPage..total step LibraryPage).toList()
            val pages: List<List<JsonObject>> = coroutineScope {
                offsets.map { offset ->
                    async<List<JsonObject>> {
                        runCatching { libraryPage(offset).array("items") ?: emptyList() }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            pages.forEach { items.addAll(it) }
        }
        return items.mapNotNull(::libraryEntry)
    }

    private fun libraryPage(offset: Int): JsonObject {
        val variables = buildJsonObjectMap(
            "filters" to listOf("Playlists"),
            "order" to null,
            "textFilter" to null,
            "features" to listOf("LIKED_SONGS"),
            "limit" to LibraryPage,
            "offset" to offset,
            "flatten" to true,
            "folderUri" to null,
            "includeFoldersWhenFlattening" to true,
        )
        val body = query("libraryV3", variables)
        return body.obj("data")?.obj("me")?.obj("libraryV3")
            ?: error("Spotify didn't return your library; sign in again.")
    }

    private fun webApiPlaylists(): List<SpotifyEntry> {
        val entries = mutableListOf<SpotifyEntry>()
        var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (url != null && pages < 20) {
            pages++
            val body = runCatching { getJson(url, auth = true) }.getOrNull() ?: break
            body.array("items").orEmpty().forEach { item ->
                val id = item.string("id") ?: return@forEach
                entries += SpotifyEntry(
                    source = SpotifySource.Playlist(id),
                    name = item.string("name").orEmpty().trim(),
                    owner = item.obj("owner")?.string("display_name").orEmpty(),
                    count = item.obj("tracks")?.int("total") ?: item.obj("items")?.int("total"),
                    art = item.array("images")?.lastOrNull()?.string("url"),
                )
            }
            url = body.string("next")
        }
        return entries
    }

    private fun toPlaylist(name: String, items: List<JsonObject>): SpotifyPlaylist {
        val tracks = mutableListOf<SpotifyTrack>()
        var skipped = 0
        items.forEach { item ->
            val track = parseTrack(item)
            if (track != null) tracks += track else skipped++
        }
        if (tracks.isEmpty()) error("That Spotify playlist has no songs.")
        return SpotifyPlaylist(name, tracks, skipped)
    }

    private fun parseTrack(item: JsonObject): SpotifyTrack? {
        var track = listOf("itemV2", "item", "track")
            .mapNotNull { item.obj(it) }
            .firstOrNull() ?: item
        track.obj("data")?.let { track = it }
        val name = track.string("name")?.trim().orEmpty()
        if (name.isBlank()) return null
        if (track.string("__typename") == "LocalTrack") {
            return SpotifyTrack(
                name = name,
                artists = splitArtists(track.string("artistName").orEmpty()),
                local = true,
            )
        }
        val artists = track.obj("artists")?.array("items")?.mapNotNull {
            it.obj("profile")?.string("name") ?: it.string("name") ?: it.obj("data")?.obj("profile")?.string("name")
        }.orEmpty()
        if (artists.isEmpty()) return null
        val art = art(track.obj("albumOfTrack")?.obj("coverArt")?.array("sources"))
        val durationMs = listOf(
            track.obj("trackDuration")?.long("totalMilliseconds"),
            track.obj("duration")?.long("totalMilliseconds"),
            track.long("duration_ms"),
        ).firstOrNull { it != null && it > 0 } ?: 0L
        return SpotifyTrack(name, artists, art = art, durationMs = durationMs)
    }

    private fun libraryEntry(item: JsonObject): SpotifyEntry? {
        val data = item.obj("data") ?: item
        return when (data.string("__typename")) {
            "PseudoPlaylist" -> if (
                data.string("uri")?.endsWith(":collection") == true || data.string("name") == "Liked Songs"
            ) {
                SpotifyEntry(SpotifySource.Liked, "Liked Songs", "", data.int("count"), null)
            } else null
            "Playlist" -> {
                val id = data.string("uri")?.removePrefix("spotify:playlist:")?.takeIf { it.isNotBlank() } ?: return null
                SpotifyEntry(
                    source = SpotifySource.Playlist(id),
                    name = data.string("name").orEmpty().trim(),
                    owner = data.obj("ownerV2")?.obj("data")?.string("name").orEmpty(),
                    count = null,
                    art = art(data.obj("images")?.array("items")?.firstOrNull()?.array("sources")),
                )
            }
            else -> null
        }
    }

    private fun art(sources: List<JsonObject>?): String? {
        val list = sources.orEmpty()
        val withWidth = list.mapNotNull { source ->
            val width = source.int("width") ?: 0
            val url = source.string("url") ?: return@mapNotNull null
            width to url
        }
        return withWidth.filter { it.first >= 60 }.minByOrNull { it.first }?.second
            ?: withWidth.firstOrNull()?.second
    }

    private fun splitArtists(value: String): List<String> =
        value.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }

    private fun query(operation: String, variables: JsonObject): JsonObject {
        val hash = hashes[operation] ?: error("Spotify's $operation query is unavailable.")
        val extensions = buildJsonObject {
            put("persistedQuery", buildJsonObject {
                put("version", 1)
                put("sha256Hash", hash)
            })
        }
        val url = "https://api-partner.spotify.com/pathfinder/v1/query".toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operation)
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", UserAgent)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Token", clientToken)
                    .header("Spotify-App-Version", clientVersion)
                    .header("Accept-Language", "en")
                    .header("Origin", "https://open.spotify.com")
                    .header("Referer", "https://open.spotify.com/")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    }
                    if (response.code == 429 || response.code in 500..599) {
                        lastError = IllegalStateException("Spotify returned HTTP ${response.code}.")
                        Thread.sleep(500L * (attempt + 1))
                        return@use
                    }
                    val errorBody = response.body?.string().orEmpty()
                    error("Spotify's $operation query failed: HTTP ${response.code} ${errorBody.take(200)} hash=${hash.take(8)}")
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Spotify's $operation query failed.")
    }

    private fun getJson(url: String, auth: Boolean): JsonObject {
        val builder = Request.Builder().url(url)
            .header("User-Agent", UserAgent)
        if (auth) builder.header("Authorization", "Bearer $accessToken")
        http.newCall(builder.build()).execute().use { response ->
            require(response.isSuccessful) { "Spotify returned HTTP ${response.code}." }
            return json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.objOrNull(): JsonObject? = this
    private fun JsonObject?.array(key: String): List<JsonObject>? =
        (this?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }
    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonElement)?.jsonPrimitive?.contentOrNull
    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonElement)?.jsonPrimitive?.intOrNull
    private fun JsonObject?.long(key: String): Long? =
        (this?.get(key) as? JsonElement)?.jsonPrimitive?.longOrNull

    companion object {
        private const val UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val SecretsUrl =
            "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json"
        private val FallbackSecret = 61 to listOf(
            44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76,
            94, 102, 43, 69, 49, 120, 118, 80, 64, 78,
        )
        private const val PageSize = 343
        private const val LibraryPage = 50
        private const val Cdn = "https://open.spotifycdn.com/cdn/build/web-player/"
        private val Operations = listOf("fetchPlaylist", "fetchLibraryTracks", "libraryV3", "profileAttributes")

        suspend fun connect(
            spDc: String?,
            requireSignedIn: Boolean = true,
        ): Result<SpotifyWebClient> = withContext(Dispatchers.IO) {
            runCatching {
                val cookieJar = MemoryCookieJar()
                val http = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .cookieJar(cookieJar)
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .build()
                fun request(url: String): okhttp3.Response = http.newCall(
                    Request.Builder().url(url).header("User-Agent", UserAgent).build(),
                ).execute()

                val html = request("https://open.spotify.com").use { response ->
                    require(response.isSuccessful) { "Spotify returned HTTP ${response.code}." }
                    response.body?.string().orEmpty()
                }
                val deviceId = cookieJar.cookies["sp_t"]?.value.orEmpty()
                val configMatch = Regex("""<script id="appServerConfig" type="text/plain">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(html) ?: error("open.spotify.com is missing appServerConfig.")
                val config = Json.parseToJsonElement(
                    Base64.decode(configMatch.groupValues[1].trim(), Base64.DEFAULT).toString(Charsets.UTF_8),
                ).jsonObject
                val clientVersion = config["clientVersion"]?.jsonPrimitive?.contentOrNull
                    ?: error("Spotify's app config has no client version.")
                val bundle = Regex("""<script[^>]*src="([^"]+)"""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { "web-player/web-player" in it && it.endsWith(".js") }
                    ?: error("open.spotify.com has no web-player bundle.")

                val secret = fetchSecret(http)
                val totp = totp(secret.second, System.currentTimeMillis() / 1000L)
                val tokenUrl = "https://open.spotify.com/api/token".toHttpUrl().newBuilder()
                    .addQueryParameter("reason", "init")
                    .addQueryParameter("productType", "web-player")
                    .addQueryParameter("totp", totp)
                    .addQueryParameter("totpServer", totp)
                    .addQueryParameter("totpVer", secret.first.toString())
                    .build()
                val cookie = buildString {
                    append("sp_t=").append(deviceId.ifBlank { "device" })
                    if (!spDc.isNullOrBlank()) append("; sp_dc=").append(spDc)
                }
                // The shell's cookie jar also holds sp_landing/sp_new and a
                // fresh sp_t. Sending those beside the explicit session header
                // makes Spotify treat the request as a guest session, so the
                // token call goes out on a client with no cookie jar.
                val tokenHttp = http.newBuilder()
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    .build()
                val tokenResponse = tokenHttp.newCall(
                    Request.Builder().url(tokenUrl)
                        .header("User-Agent", UserAgent)
                        .header("Cookie", cookie)
                        .build(),
                ).execute().use { response ->
                    require(response.isSuccessful) { "Spotify refused the access token (HTTP ${response.code})." }
                    Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }
                val anonymous = tokenResponse["isAnonymous"]?.jsonPrimitive?.contentOrNull
                if (requireSignedIn && !spDc.isNullOrBlank() && anonymous == "true") {
                    error("Your Spotify sign-in has expired. Sign in again.")
                }
                val accessToken = tokenResponse["accessToken"]?.jsonPrimitive?.contentOrNull
                    ?: error("Spotify returned no access token.")
                val clientId = tokenResponse["clientId"]?.jsonPrimitive?.contentOrNull
                    ?: error("Spotify returned no client id.")

                val clientTokenPayload = buildJsonObject {
                    put("client_data", buildJsonObject {
                        put("client_version", clientVersion)
                        put("client_id", clientId)
                        put("js_sdk_data", buildJsonObject {
                            put("device_brand", "unknown")
                            put("device_model", "unknown")
                            put("os", "windows")
                            put("os_version", "NT 10.0")
                            put("device_id", deviceId.ifBlank { "nuvio" })
                            put("device_type", "computer")
                        })
                    })
                }
                val clientTokenResponse = http.newCall(
                    Request.Builder().url("https://clienttoken.spotify.com/v1/clienttoken")
                        .header("User-Agent", UserAgent)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "en")
                        .header("Origin", "https://open.spotify.com")
                        .header("Referer", "https://open.spotify.com/")
                        .post(
                            clientTokenPayload.toString().toByteArray(Charsets.UTF_8)
                                .toRequestBody("application/json".toMediaType()),
                        )
                        .build(),
                ).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(
                            "Spotify refused the client token (HTTP ${response.code}): ${bodyText.take(120)} " +
                                "version=$clientVersion client=${clientId.length} device=${deviceId.length}",
                        )
                    }
                    Json.parseToJsonElement(bodyText).jsonObject
                }
                val clientToken = clientTokenResponse["granted_token"]?.jsonObject
                    ?.get("token")?.jsonPrimitive?.contentOrNull
                    ?: error("Spotify returned no client token.")

                val hashes = queryHashes(http, bundle)
                SpotifyWebClient(
                    accessToken = accessToken,
                    clientToken = clientToken,
                    clientVersion = clientVersion,
                    hashes = hashes,
                    signedIn = !spDc.isNullOrBlank(),
                )
            }
        }

        private fun fetchSecret(http: OkHttpClient): Pair<Int, List<Int>> = runCatching {
            http.newCall(Request.Builder().url(SecretsUrl).header("User-Agent", UserAgent).build())
                .execute().use { response ->
                    require(response.isSuccessful)
                    val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val entries = root.entries.mapNotNull { (key, value) ->
                        val version = key.toIntOrNull() ?: return@mapNotNull null
                        val bytes = (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                            ?: return@mapNotNull null
                        version to bytes
                    }
                    entries.maxByOrNull { it.first } ?: error("empty secrets")
                }
        }.getOrDefault(FallbackSecret.first to FallbackSecret.second)

        private fun totp(secret: List<Int>, unixSeconds: Long): String {
            val key = secret.mapIndexed { index, byte ->
                (byte xor ((index % 33) + 9)).toString()
            }.joinToString("")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val counter = unixSeconds / 30
            val hash = mac.doFinal(ByteArray(8) { index -> ((counter shr (8 * (7 - index))) and 0xff).toByte() })
            val offset = hash[19].toInt() and 0xf
            val code = (((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff))
            return (code % 1_000_000).toString().padStart(6, '0')
        }

        private fun queryHashes(http: OkHttpClient, bundle: String): Map<String, String> {
            val js = http.newCall(Request.Builder().url(bundle).header("User-Agent", UserAgent).build())
                .execute().use { response ->
                    require(response.isSuccessful) { "Could not read Spotify's web-player bundle." }
                    response.body?.string().orEmpty()
                }
            val hashes = mutableMapOf<String, String>()
            Operations.forEach { operation ->
                Regex("\"$operation\",\"query\",\"([0-9a-f]{64})\"").find(js)?.let {
                    hashes[operation] = it.groupValues[1]
                }
            }
            if (!hashes.containsKey("fetchPlaylist")) {
                error("Spotify changed its web player; the transfer needs an update.")
            }
            return hashes
        }
    }
}

private fun buildJsonObjectMap(vararg values: Pair<String, Any?>): JsonObject {
    val map = mutableMapOf<String, JsonElement>()
    values.forEach { (key, value) ->
        map[key] = when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is Int -> kotlinx.serialization.json.JsonPrimitive(value)
            is Long -> kotlinx.serialization.json.JsonPrimitive(value)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
            is String -> kotlinx.serialization.json.JsonPrimitive(value)
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }
    }
    return JsonObject(map)
}

private class MemoryCookieJar : CookieJar {
    val cookies = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.matches(url) }
}
