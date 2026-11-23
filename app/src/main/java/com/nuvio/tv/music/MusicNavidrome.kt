package com.nuvio.tv.music

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class NavidromeConfig(
    val url: String = "",
    val username: String = "",
    val token: String = "",
    val salt: String = ""
) {
    val configured: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && token.isNotBlank() && salt.isNotBlank()
}

data class NavidromeAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val coverArt: String?,
    val songCount: Int
)

data class NavidromePlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val songCount: Int,
    val coverArt: String?
)

data class NavidromeAlbumDetail(
    val album: NavidromeAlbum,
    val songs: List<Song>
)

data class NavidromePlaylistDetail(
    val playlist: NavidromePlaylist,
    val songs: List<Song>
)

/**
 * A small Subsonic API client for Navidrome.
 *
 * Authentication uses Subsonic's token scheme: a random salt is generated once, the token is
 * MD5(password + salt), and only the token + salt are stored. That keeps the password out of
 * preferences and out of the stream URLs.
 */
object MusicNavidrome {
    private const val PREFS = "nuvio_music_navidrome"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_TOKEN = "token"
    private const val KEY_SALT = "salt"
    private const val API_VERSION = "1.16.1"
    private const val CLIENT = "Nuvio"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow(NavidromeConfig())
    val config: StateFlow<NavidromeConfig> = _config.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = NavidromeConfig(
            url = prefs.getString(KEY_URL, "").orEmpty(),
            username = prefs.getString(KEY_USER, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            salt = prefs.getString(KEY_SALT, "").orEmpty()
        )
    }

    /** Tests the server, then stores only the derived token and salt. */
    fun connect(context: Context, url: String, username: String, password: String): Result<Unit> {
        val app = context.applicationContext
        val base = normalizeUrl(url)
            .getOrElse { return Result.failure(it) }
        val user = username.trim()
        if (user.isBlank()) return Result.failure(IllegalArgumentException("Enter a username."))
        if (password.isBlank()) return Result.failure(IllegalArgumentException("Enter a password."))
        val salt = newSalt()
        val token = md5(password + salt)
        val candidate = NavidromeConfig(base, user, token, salt)
        return request(candidate, "ping", emptyMap()).map {
            _config.value = candidate
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_URL, candidate.url)
                .putString(KEY_USER, candidate.username)
                .putString(KEY_TOKEN, candidate.token)
                .putString(KEY_SALT, candidate.salt)
                .apply()
        }
    }

    fun disconnect(context: Context) {
        _config.value = NavidromeConfig()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun albums(limit: Int = 60): Result<List<NavidromeAlbum>> {
        val config = _config.value
        if (!config.configured) return Result.failure(IllegalStateException("Navidrome isn't connected."))
        return request(
            config,
            "getAlbumList2",
            mapOf("type" to "newest", "size" to limit.toString(), "offset" to "0")
        ).map { root ->
            val list = root.optJSONObject("albumList2")?.optJSONArray("album")
            buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val item = list?.optJSONObject(i) ?: continue
                    add(
                        NavidromeAlbum(
                            id = item.optString("id"),
                            name = item.optString("name").ifBlank { item.optString("title") },
                            artist = item.optString("artist"),
                            coverArt = item.optString("coverArt").takeIf { it.isNotBlank() },
                            songCount = item.optInt("songCount", 0)
                        )
                    )
                }
            }
        }
    }

    fun playlists(): Result<List<NavidromePlaylist>> {
        val config = _config.value
        if (!config.configured) return Result.failure(IllegalStateException("Navidrome isn't connected."))
        return request(config, "getPlaylists", emptyMap()).map { root ->
            val list = root.optJSONObject("playlists")?.optJSONArray("playlist")
            buildList {
                for (i in 0 until (list?.length() ?: 0)) {
                    val item = list?.optJSONObject(i) ?: continue
                    add(
                        NavidromePlaylist(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            owner = item.optString("owner").takeIf { it.isNotBlank() },
                            songCount = item.optInt("songCount", 0),
                            coverArt = item.optString("coverArt").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }

    fun album(id: String): Result<NavidromeAlbumDetail> {
        val config = _config.value
        if (!config.configured) return Result.failure(IllegalStateException("Navidrome isn't connected."))
        return request(config, "getAlbum", mapOf("id" to id)).map { root ->
            val album = root.optJSONObject("album")
                ?: return@map NavidromeAlbumDetail(
                    album = NavidromeAlbum(id, "", "", null, 0),
                    songs = emptyList()
                )
            val songs = album.optJSONArray("song").toSongs(config)
            NavidromeAlbumDetail(
                album = NavidromeAlbum(
                    id = album.optString("id", id),
                    name = album.optString("name").ifBlank { album.optString("title") },
                    artist = album.optString("artist"),
                    coverArt = album.optString("coverArt").takeIf { it.isNotBlank() },
                    songCount = songs.size
                ),
                songs = songs
            )
        }
    }

    fun playlist(id: String): Result<NavidromePlaylistDetail> {
        val config = _config.value
        if (!config.configured) return Result.failure(IllegalStateException("Navidrome isn't connected."))
        return request(config, "getPlaylist", mapOf("id" to id)).map { root ->
            val playlist = root.optJSONObject("playlist")
                ?: return@map NavidromePlaylistDetail(
                    playlist = NavidromePlaylist(id, "", null, 0, null),
                    songs = emptyList()
                )
            val songs = playlist.optJSONArray("entry").toSongs(config)
            NavidromePlaylistDetail(
                playlist = NavidromePlaylist(
                    id = playlist.optString("id", id),
                    name = playlist.optString("name"),
                    owner = playlist.optString("owner").takeIf { it.isNotBlank() },
                    songCount = songs.size,
                    coverArt = playlist.optString("coverArt").takeIf { it.isNotBlank() }
                ),
                songs = songs
            )
        }
    }

    fun scrobble(song: Song, submission: Boolean = true): Result<Unit> {
        val config = _config.value
        if (!config.configured) return Result.success(Unit)
        val id = song.videoId.removePrefix("navidrome:")
        return request(
            config,
            "scrobble",
            mapOf("id" to id, "submission" to submission.toString())
        ).map { }
    }

    fun setStarred(song: Song, starred: Boolean): Result<Unit> {
        val config = _config.value
        if (!config.configured) return Result.success(Unit)
        val id = song.videoId.removePrefix("navidrome:")
        return request(
            config,
            if (starred) "star" else "unstar",
            mapOf("id" to id)
        ).map { }
    }

    fun coverUrl(config: NavidromeConfig, coverId: String?, size: Int = 480): String? {
        if (!config.configured || coverId.isNullOrBlank()) return null
        return apiUrl(
            config,
            "getCoverArt",
            mapOf("id" to coverId, "size" to size.toString())
        )
    }

    private fun request(
        config: NavidromeConfig,
        endpoint: String,
        params: Map<String, String>
    ): Result<JSONObject> = runCatching {
        val url = apiUrl(config, endpoint, params)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NuvioOne/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Navidrome returned HTTP ${response.code}." }
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body).optJSONObject("subsonic-response")
                ?: error("Navidrome returned an unexpected response.")
            if (root.optString("status") != "ok") {
                val message = root.optJSONObject("error")?.optString("message")
                error(message?.takeIf { it.isNotBlank() } ?: "Navidrome rejected the connection.")
            }
            root
        }
    }

    private fun apiUrl(
        config: NavidromeConfig,
        endpoint: String,
        params: Map<String, String>
    ): String {
        val base = config.url.trimEnd('/')
        val query = linkedMapOf(
            "u" to config.username,
            "t" to config.token,
            "s" to config.salt,
            "v" to API_VERSION,
            "c" to CLIENT,
            "f" to "json"
        ) + params
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$base/rest/$endpoint?$queryString"
    }

    private fun org.json.JSONArray?.toSongs(config: NavidromeConfig): List<Song> {
        val songs = mutableListOf<Song>()
        for (i in 0 until (this?.length() ?: 0)) {
            val item = this?.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val duration = item.optInt("duration", 0)
            val cover = item.optString("coverArt").takeIf { it.isNotBlank() }
            songs += Song(
                videoId = "navidrome:$id",
                title = item.optString("title").ifBlank { "Unknown track" },
                artist = item.optString("artist").ifBlank { "Navidrome" },
                thumbnailUrl = coverUrl(config, cover),
                durationText = formatDuration(duration),
                albumId = item.optString("albumId").takeIf { it.isNotBlank() },
                albumName = item.optString("album").takeIf { it.isNotBlank() },
                localUri = apiUrl(config, "stream", mapOf("id" to id, "format" to "raw"))
            )
        }
        return songs
    }

    private fun normalizeUrl(raw: String): Result<String> {
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return Result.failure(
                IllegalArgumentException("The server URL must start with http:// or https://.")
            )
        }
        return Result.success(trimmed)
    }

    private fun newSalt(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }
}

/** Sends Subsonic now-playing and scrobble calls for tracks streamed from Navidrome. */
object MusicNavidromeSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var announcedId: String? = null
    private var scrobbledId: String? = null
    private var lastSongId: String? = null
    private var lastPositionMs = 0L

    @Synchronized
    fun init(context: Context) {
        MusicNavidrome.init(context)
        if (started) return
        started = true
        scope.launch {
            MusicPlayer.state.collect { state ->
                if (!MusicNavidrome.config.value.configured) return@collect
                val song = state.song ?: return@collect
                if (!song.videoId.startsWith("navidrome:")) return@collect
                if (song.videoId != lastSongId) {
                    lastSongId = song.videoId
                    announcedId = null
                    scrobbledId = null
                    lastPositionMs = 0L
                }
                if (state.positionMs + 5_000L < lastPositionMs) {
                    announcedId = null
                    scrobbledId = null
                }
                lastPositionMs = state.positionMs
                if (state.isPlaying && announcedId != song.videoId) {
                    announcedId = song.videoId
                    MusicNavidrome.scrobble(song, submission = false)
                }
                if (scrobbledId == song.videoId || !state.isPlaying) return@collect
                val duration = state.durationMs.takeIf { it > 0L } ?: song.durationMillis()
                if (duration < 30_000L) return@collect
                if (state.positionMs >= min(duration / 2L, 4 * 60_000L)) {
                    scrobbledId = song.videoId
                    MusicNavidrome.scrobble(song, submission = true)
                }
            }
        }
    }
}
