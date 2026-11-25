package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One addon the listener pointed the Music section at. */
data class MusicAddonConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    /** The renditions this addon's manifest offers, when it declares a `quality` select. */
    val qualities: List<MusicAddonOption> = emptyList(),
    /** The value sent as `quality`; null means the addon's first (best) option. */
    val quality: String? = null,
    /** Manifest `checkValidLossless`: only ask this source when the output can carry lossless. */
    val requiresLossless: Boolean = false,
    /** Manifest `bitchordAuth`: the addon only serves the signed BitChord app. */
    val requiresBitChordAuth: Boolean = false
)

data class MusicAddonOption(val value: String, val label: String)

data class MusicAddonSetting(
    val key: String,
    val defaultValue: String?,
    val options: List<MusicAddonOption>
)

/** The description an addon serves at `/manifest.json`. */
data class MusicAddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val resources: List<String>,
    val settings: List<MusicAddonSetting>,
    val requiresLosslessOutput: Boolean = false,
    val requiresBitChordAuth: Boolean = false
) {
    val displayName: String get() = name.ifBlank { id }

    /** `search` is the only endpoint this section requires; silence means "ask anyway". */
    val playable: Boolean
        get() = resources.isEmpty() || resources.any { it.equals("search", ignoreCase = true) }
}

data class MusicAddonTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int?,
    val artworkUrl: String?,
    val format: String?,
    val quality: String?,
    val streamUrl: String?
)

data class MusicAddonStream(
    val url: String,
    val format: String?,
    val quality: String?,
    val manifest: String?,
    val mimeType: String?,
    /** The configured source's display name, filled in by the registry. */
    val sourceName: String? = null
)

data class MusicAddonSearchHit(
    val config: MusicAddonConfig,
    val track: MusicAddonTrack
)

/** What ExoPlayer should be told this URL is, from whichever field the addon filled in. */
internal fun MusicAddonStream.resolvedMimeType(): String? {
    mimeType?.takeIf { it.isNotBlank() }?.let { return it }
    when (manifest?.lowercase()) {
        "dash" -> return "application/dash+xml"
        "hls" -> return "application/x-mpegURL"
    }
    return when (format?.lowercase()) {
        "dash" -> "application/dash+xml"
        "hls", "m3u8" -> "application/x-mpegURL"
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac", "alac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }
}

/**
 * A user-added HTTP music addon.
 *
 * The protocol is three JSON GETs and nothing else: `manifest.json`, `search?q=` and
 * `stream/{id}`. No addon code is downloaded or executed here.
 */
internal class MusicAddonClient(
    rawBaseUrl: String,
    private val pickedQuality: String? = null
) {
    val baseUrl: String = normalizeBase(rawBaseUrl)

    @Volatile
    private var cachedManifest: Pair<Long, MusicAddonManifest>? = null

    @Volatile
    private var manifestRetryAt = 0L

    @Volatile
    private var lastManifestError: String? = null

    fun manifest(): Result<MusicAddonManifest> = runCatching {
        cachedManifest?.takeIf { System.currentTimeMillis() - it.first < MANIFEST_TTL_MS }?.second
            ?.let { return@runCatching it }
        if (System.currentTimeMillis() < manifestRetryAt) {
            throw IOException(lastManifestError ?: "The addon's manifest couldn't be read.")
        }
        val parsed = runCatching { parseManifest(get(manifestUrl())) }
            .recoverCatching { parseManifest(get(baseUrl)) }
            .getOrThrow()
        cachedManifest = System.currentTimeMillis() to parsed
        lastManifestError = null
        manifestRetryAt = 0L
        parsed
    }.onFailure {
        lastManifestError = it.message ?: "The addon's manifest couldn't be read."
        manifestRetryAt = System.currentTimeMillis() + MANIFEST_RETRY_MS
    }

    fun search(query: String): Result<List<MusicAddonTrack>> = runCatching {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@runCatching emptyList()
        val params = settingsParams() + ("q" to trimmed)
        parseTracks(get(endpoint(listOf("search"), params)))
    }

    fun stream(trackId: String): Result<MusicAddonStream> = runCatching {
        parseStream(get(endpoint(listOf("stream", trackId), settingsParams())))
    }

    private fun manifestUrl(): String = "$baseUrl/manifest.json"

    private fun endpoint(path: List<String>, query: Map<String, String>): String {
        val parsed = baseUrl.toHttpUrlOrNull()
            ?: throw IOException("That isn't a valid addon URL.")
        val builder = parsed.newBuilder()
        path.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    /**
     * The settings every request carries.
     *
     * Addons declare defaults and, often, a `quality` select. The first listed option is the best
     * one they offer, which is what a listener adding a lossless source expects until a per-addon
     * picker exists.
     */
    private fun settingsParams(): Map<String, String> {
        val params = linkedMapOf<String, String>()
        val manifest = manifest().getOrNull()
        manifest?.settings.orEmpty().forEach { setting ->
            setting.defaultValue?.let { params[setting.key] = it }
        }
        val options = manifest?.settings?.firstOrNull { it.key == QUALITY_KEY }?.options.orEmpty()
        params[QUALITY_KEY] = pickedQuality?.takeIf { picked -> options.any { it.value == picked } }
            ?: options.firstOrNull()?.value
            ?: pickedQuality
            ?: "LOSSLESS"
        return params
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        HTTP.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val challenge = response.header("WWW-Authenticate").orEmpty()
                if (response.code == 401 && challenge.contains("BitChord", ignoreCase = true)) {
                    throw IOException(
                        "This addon requires BitChord app verification, which Nuvio can't provide."
                    )
                }
                throw IOException(
                    when (response.code) {
                        401, 403 -> "This addon asks for a sign-in Nuvio doesn't support yet."
                        404 -> "That addon doesn't hold this track."
                        429 -> "This addon is rate limiting requests. Try again shortly."
                        else -> "The addon returned HTTP ${response.code}."
                    }
                )
            }
            return body
        }
    }

    private fun parseManifest(body: String): MusicAddonManifest {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("That URL answered, but not with an addon manifest.")
        }
        val id = json.string("id")
        if (id.isBlank()) throw IOException("That URL answered, but not with an addon manifest.")
        val resources = buildList {
            json.optJSONArray("resources")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        val settings = buildList {
            json.optJSONArray("settings")?.let { array ->
                for (index in 0 until array.length()) {
                    val setting = array.optJSONObject(index) ?: continue
                    val key = setting.string("key")
                    if (key.isBlank()) continue
                    val options = buildList {
                        setting.optJSONArray("options")?.let { optionArray ->
                            for (optionIndex in 0 until optionArray.length()) {
                                val option = optionArray.optJSONObject(optionIndex) ?: continue
                                val value = option.queryValue("value") ?: continue
                                add(MusicAddonOption(value, option.queryValue("label") ?: value))
                            }
                        }
                    }
                    add(
                        MusicAddonSetting(
                            key = key,
                            defaultValue = setting.queryValue("default"),
                            options = options
                        )
                    )
                }
            }
        }
        val manifest = MusicAddonManifest(
            id = id,
            name = json.string("name"),
            version = json.string("version"),
            resources = resources,
            settings = settings,
            requiresLosslessOutput = json.flag("checkValidLossless"),
            requiresBitChordAuth = json.flag("bitchordAuth")
        )
        if (!manifest.playable) {
            throw IOException("This addon doesn't offer search.")
        }
        return manifest
    }

    private fun parseTracks(body: String): List<MusicAddonTrack> {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("The addon's search answer wasn't JSON.")
        }
        val array = json.optJSONArray("tracks") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val track = array.optJSONObject(index) ?: continue
                val id = track.string("id")
                if (id.isBlank()) continue
                val duration = track.durationSeconds()
                add(
                    MusicAddonTrack(
                        id = id,
                        title = track.string("title").ifBlank { "Untitled track" },
                        artist = track.string("artist"),
                        album = track.string("album").takeIf { it.isNotBlank() },
                        durationSeconds = duration,
                        artworkUrl = absoluteUrl(track.string("artworkURL"))
                            ?: absoluteUrl(track.string("albumArtworkURL")),
                        format = track.string("format").takeIf { it.isNotBlank() },
                        quality = track.string("audioQuality").takeIf { it.isNotBlank() }
                            ?: track.string("quality").takeIf { it.isNotBlank() },
                        streamUrl = absoluteUrl(track.string("streamURL"))
                    )
                )
            }
        }
    }

    private fun parseStream(body: String): MusicAddonStream {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("The addon's stream answer wasn't JSON.")
        }
        val encrypted = json.opt("encrypted")
        if (encrypted is Boolean && encrypted) {
            throw IOException("This addon returned a DRM-protected stream.")
        }
        if (encrypted is String && encrypted.isNotBlank() &&
            !encrypted.equals("false", ignoreCase = true) &&
            !encrypted.equals("none", ignoreCase = true)
        ) {
            throw IOException("This addon returned a DRM-protected stream.")
        }
        val url = absoluteUrl(json.string("url"))
        if (url.isNullOrBlank()) {
            throw IOException(json.string("error").ifBlank { "This addon didn't return a stream." })
        }
        return MusicAddonStream(
            url = url,
            format = json.string("format").takeIf { it.isNotBlank() },
            quality = json.string("quality").takeIf { it.isNotBlank() }
                ?: json.string("audioQuality").takeIf { it.isNotBlank() },
            manifest = json.string("manifest").takeIf { it.isNotBlank() }
                ?: json.string("mediaType").takeIf { it.isNotBlank() },
            mimeType = json.string("mimeType").takeIf { it.isNotBlank() }
        )
    }

    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        return baseUrl.toHttpUrlOrNull()?.resolve(value)?.toString()
    }

    companion object {
        private const val USER_AGENT = "NuvioOne/1.0"
        private const val MANIFEST_TTL_MS = 10 * 60 * 1000L
        private const val MANIFEST_RETRY_MS = 60 * 1000L
        private const val QUALITY_KEY = "quality"

        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        /** Accepts either the addon root or its manifest URL, both of which people copy. */
        fun normalizeBase(raw: String): String {
            var trimmed = raw.trim().trimEnd('/')
            if (!trimmed.contains("://")) {
                val host = trimmed.substringBefore('/')
                val local = host.startsWith("localhost") || host.startsWith("127.") ||
                    host.startsWith("192.168.") || host.startsWith("10.") ||
                    host.startsWith("[::1]")
                trimmed = "${if (local) "http" else "https"}://$trimmed"
            }
            val withoutQuery = trimmed.substringBefore('?')
            val lastSegment = withoutQuery.substringAfterLast('/', "")
            if (!lastSegment.endsWith(".json", ignoreCase = true) || !trimmed.contains("://")) {
                return trimmed
            }
            val query = trimmed.substringAfter('?', "")
            val path = withoutQuery.dropLast(lastSegment.length).trimEnd('/')
            return if (query.isBlank()) path else "$path?$query"
        }
    }
}

/**
 * The addons the listener has added, and the one place a track key becomes an addon request.
 *
 * Keys are `addon:<config id>:<base64 track id>` so a [Song] can travel through the existing
 * queue, history and scrobbler code without any of them knowing where it came from.
 */
object MusicAddonRegistry {
    const val TRACK_PREFIX = "addon:"

    private const val TAG = "MusicAddons"
    /** Pre-encryption file; read once by [MusicSecurePreferences] and then removed. */
    private const val LEGACY_PREFS = "nuvio_music_addons"
    private const val SECURE_PREFS = "nuvio_music_addons_secure"
    private const val PLAIN_PREFS = "nuvio_music_addons_plain"
    private const val KEY_CONFIGS = "configs"
    private const val MAX_RESULTS_PER_ADDON = 30
    private const val DIRECT_STREAM_TTL_MS = 4 * 60 * 1000L
    private const val SUBSTITUTION_TTL_MS = 4 * 60 * 1000L
    private const val SUBSTITUTION_MISS_TTL_MS = 2 * 60 * 1000L
    private const val SUBSTITUTION_BUDGET_MS = 6000L

    private val _configs = MutableStateFlow<List<MusicAddonConfig>>(emptyList())
    val configs: StateFlow<List<MusicAddonConfig>> = _configs.asStateFlow()

    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var loaded = false
    private val clients = ConcurrentHashMap<String, MusicAddonClient>()

    /** Rows that carried their own `streamURL`, so playback skips `/stream/{id}` entirely. */
    private val directStreams = ConcurrentHashMap<String, Pair<Long, MusicAddonStream>>()

    private data class CachedSubstitution(
        val savedAt: Long,
        val configId: String,
        val stream: MusicAddonStream
    )

    /** YouTube video id -> an addon stream already matched to it. */
    private val substitutions = ConcurrentHashMap<String, CachedSubstitution>()
    private val substitutionMisses = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        this.context = app
        MusicLosslessOutput.init(app)
        if (loaded) return
        prefs = MusicSecurePreferences.open(app, SECURE_PREFS, PLAIN_PREFS, LEGACY_PREFS)
        loaded = true
        load()
    }

    fun isAddonTrack(videoId: String): Boolean = videoId.startsWith(TRACK_PREFIX)

    /** The configured source name behind an addon track key, for the Now Playing tagline. */
    fun sourceName(videoId: String): String? {
        val (configId, _) = parseTrackKey(videoId) ?: return null
        return _configs.value.firstOrNull { it.id == configId }?.name
    }

    fun trackKey(configId: String, trackId: String): String {
        val encoded = Base64.encodeToString(
            trackId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$TRACK_PREFIX$configId:$encoded"
    }

    fun parseTrackKey(videoId: String): Pair<String, String>? {
        if (!isAddonTrack(videoId)) return null
        val rest = videoId.removePrefix(TRACK_PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0 || separator == rest.lastIndex) return null
        val configId = rest.substring(0, separator)
        val encoded = rest.substring(separator + 1)
        val trackId = runCatching {
            String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return configId to trackId
    }

    /**
     * Tests an addon without saving it.
     *
     * A manifest is the normal path. Some servers only implement the two endpoints, so a failed
     * manifest falls back to asking `/search` for a probe query and accepting that answer.
     */
    fun test(rawUrl: String): Result<MusicAddonManifest> {
        val client = MusicAddonClient(rawUrl)
        val manifest = client.manifest()
        if (manifest.isSuccess) return manifest
        val probe = client.search("test")
        if (probe.isFailure) return manifest
        val base = MusicAddonClient.normalizeBase(rawUrl)
        val host = base.substringAfter("://").substringBefore('/').ifBlank { base }
        return Result.success(
            MusicAddonManifest(
                id = base,
                name = host,
                version = "",
                resources = listOf("search", "stream"),
                settings = emptyList()
            )
        )
    }

    fun add(rawUrl: String): Result<MusicAddonConfig> = test(rawUrl).mapCatching { manifest ->
        if (manifest.requiresBitChordAuth) {
            throw IOException(
                "This addon requires BitChord app verification, which Nuvio can't provide."
            )
        }
        val base = MusicAddonClient.normalizeBase(rawUrl)
        val existing = _configs.value.firstOrNull { it.baseUrl == base }
        val qualities = manifest.settings
            .firstOrNull { it.key == "quality" }
            ?.options
            .orEmpty()
        val config = existing?.copy(
            name = manifest.displayName,
            enabled = true,
            qualities = qualities,
            quality = existing.quality?.takeIf { chosen -> qualities.any { it.value == chosen } },
            requiresLossless = manifest.requiresLosslessOutput,
            requiresBitChordAuth = manifest.requiresBitChordAuth
        ) ?: MusicAddonConfig(
            id = UUID.randomUUID().toString(),
            name = manifest.displayName,
            baseUrl = base,
            enabled = true,
            qualities = qualities,
            requiresLossless = manifest.requiresLosslessOutput,
            requiresBitChordAuth = manifest.requiresBitChordAuth
        )
        clients.remove(config.id)
        directStreams.clear()
        substitutions.clear()
        substitutionMisses.clear()
        val updated = _configs.value.filterNot { it.id == config.id } + config
        _configs.value = updated
        persist(updated)
        config
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val updated = _configs.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _configs.value = updated
        substitutions.clear()
        substitutionMisses.clear()
        persist(updated)
    }

    /** Picks the `quality` value this addon receives; it is cleared on the next request cache. */
    fun setQuality(id: String, quality: String?) {
        val updated = _configs.value.map { if (it.id == id) it.copy(quality = quality) else it }
        _configs.value = updated
        clients.remove(id)
        directStreams.clear()
        substitutions.clear()
        substitutionMisses.clear()
        persist(updated)
    }

    fun remove(id: String) {
        clients.remove(id)
        directStreams.clear()
        substitutions.clear()
        substitutionMisses.clear()
        val updated = _configs.value.filterNot { it.id == id }
        _configs.value = updated
        persist(updated)
    }

    /** Searches every enabled addon in parallel; a slow or broken one never holds up the rest. */
    suspend fun search(query: String): List<MusicAddonSearchHit> = withContext(Dispatchers.IO) {
        val enabled = _configs.value.filter {
            it.enabled && (!it.requiresLossless || MusicLosslessOutput.capable)
        }
        if (enabled.isEmpty() || query.isBlank()) return@withContext emptyList()
        val hits = coroutineScope {
            enabled.map { config ->
                async {
                    runCatching { clientFor(config).search(query).getOrThrow() }
                        .onFailure { Log.w(TAG, "search failed for ${config.name}: ${it.message}") }
                        .getOrNull()
                        .orEmpty()
                        .take(MAX_RESULTS_PER_ADDON)
                        .map { MusicAddonSearchHit(config, it) }
                }
            }.awaitAll().flatten()
        }
        rememberDirectStreams(hits)
        hits
    }

    private fun rememberDirectStreams(hits: List<MusicAddonSearchHit>) {
        if (directStreams.size > 512) directStreams.clear()
        hits.forEach { hit ->
            val url = hit.track.streamUrl?.takeIf { it.isNotBlank() } ?: return@forEach
            directStreams[trackKey(hit.config.id, hit.track.id)] =
                System.currentTimeMillis() to MusicAddonStream(
                    url = url,
                    format = hit.track.format,
                    quality = hit.track.quality,
                    manifest = null,
                    mimeType = null,
                    sourceName = hit.config.name
                )
        }
    }

    /**
     * The same recording from an addon, for an opt-in YouTube substitution.
     *
     * Only rows with a known runtime are considered, and [TrackMatcher] still requires the
     * artist and duration to line up. Successful and failed lookups are cached briefly so a
     * queue does not repeat the same searches on every play.
     */
    suspend fun substitute(song: Song): MusicAddonStream? {
        if (song.isVideo || song.title.isBlank()) return null
        val now = System.currentTimeMillis()
        substitutions[song.videoId]?.let { cached ->
            val source = _configs.value.firstOrNull { it.id == cached.configId }
            val usable = now - cached.savedAt < SUBSTITUTION_TTL_MS &&
                source != null &&
                source.enabled &&
                (!source.requiresLossless || MusicLosslessOutput.capable)
            if (usable) return cached.stream
            substitutions.remove(song.videoId)
        }
        substitutionMisses[song.videoId]?.let { missedAt ->
            if (now - missedAt < SUBSTITUTION_MISS_TTL_MS) return null
            substitutionMisses.remove(song.videoId)
        }
        val target = TrackMatcher.targetOf(song)
        if (target.artist.isBlank()) return null
        val queries = TrackMatcher.queries(target).take(2)
        if (queries.isEmpty()) return null
        val available = _configs.value.count {
            it.enabled && (!it.requiresLossless || MusicLosslessOutput.capable)
        }
        if (available == 0) return null

        val deadline = now + SUBSTITUTION_BUDGET_MS
        for (query in queries) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val hits = withTimeoutOrNull(remaining) { search(query) }.orEmpty()
            if (hits.isEmpty()) continue
            val byId = LinkedHashMap<String, Pair<MusicAddonSearchHit, Song>>()
            hits.forEach { hit ->
                val candidate = hit.track.toSong(hit.config)
                byId[candidate.videoId] = hit to candidate
            }
            val ranked = TrackMatcher.ranked(byId.values.map { it.second }, target)
            val order = _configs.value.withIndex().associate { (index, config) -> config.id to index }
            // Check add-ons in the order the listener added them; only move to the next
            // add-on when this one has no confident match.
            val ordered = ranked.mapNotNull { candidate ->
                byId[candidate.videoId]?.let { (source, _) -> source to candidate }
            }.sortedBy { (source, _) -> order[source.config.id] ?: Int.MAX_VALUE }
            for ((source, candidate) in ordered.take(4)) {
                if (System.currentTimeMillis() >= deadline) break
                val stream = resolveStream(candidate.videoId).getOrNull() ?: continue
                if (substitutions.size > 256) substitutions.clear()
                substitutions[song.videoId] = CachedSubstitution(
                    savedAt = System.currentTimeMillis(),
                    configId = source.config.id,
                    stream = stream
                )
                return stream
            }
        }
        if (substitutionMisses.size > 512) substitutionMisses.clear()
        substitutionMisses[song.videoId] = System.currentTimeMillis()
        return null
    }

    /** Blocking; called from the stream resolver's IO path. */
    fun resolveStream(videoId: String): Result<MusicAddonStream> {
        val (configId, trackId) = parseTrackKey(videoId)
            ?: return Result.failure(IllegalArgumentException("That isn't an addon track."))
        val config = _configs.value.firstOrNull { it.id == configId }
            ?: return Result.failure(
                IllegalStateException("This track came from an addon that is no longer configured.")
            )
        if (!config.enabled) {
            return Result.failure(
                IllegalStateException("${config.name} is turned off in Music sources.")
            )
        }
        if (config.requiresLossless && !MusicLosslessOutput.capable) {
            return Result.failure(
                IllegalStateException(
                    "${config.name} needs a lossless-capable output (HDMI, USB or wired)."
                )
            )
        }
        directStreams[videoId]?.let { (savedAt, stream) ->
            if (System.currentTimeMillis() - savedAt < DIRECT_STREAM_TTL_MS) {
                return Result.success(stream.copy(sourceName = config.name))
            }
            directStreams.remove(videoId)
        }
        return clientFor(config).stream(trackId).map { it.copy(sourceName = config.name) }
    }

    private fun clientFor(config: MusicAddonConfig): MusicAddonClient =
        clients.getOrPut(config.id) { MusicAddonClient(config.baseUrl, config.quality) }

    private fun load() {
        val store = prefs ?: return
        val raw = store.getString(KEY_CONFIGS, "[]").orEmpty()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val baseUrl = item.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
                    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) continue
                    val qualities = buildList {
                        item.optJSONArray("qualities")?.let { array ->
                            for (optionIndex in 0 until array.length()) {
                                val option = array.optJSONObject(optionIndex) ?: continue
                                val value = option.optString("value").takeIf { it.isNotBlank() }
                                    ?: continue
                                add(
                                    MusicAddonOption(
                                        value = value,
                                        label = option.optString("label").ifBlank { value }
                                    )
                                )
                            }
                        }
                    }
                    add(
                        MusicAddonConfig(
                            id = id,
                            name = item.optString("name").ifBlank { baseUrl },
                            baseUrl = baseUrl,
                            enabled = item.optBoolean("enabled", true),
                            qualities = qualities,
                            quality = item.optString("quality").takeIf { it.isNotBlank() },
                            requiresLossless = item.optBoolean("requiresLossless", false),
                            requiresBitChordAuth = item.optBoolean("requiresBitChordAuth", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        _configs.value = parsed
    }

    private fun persist(configs: List<MusicAddonConfig>) {
        val store = prefs ?: return
        val array = JSONArray()
        configs.forEach { config ->
            val qualities = JSONArray()
            config.qualities.forEach { option ->
                qualities.put(JSONObject().put("value", option.value).put("label", option.label))
            }
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("baseUrl", config.baseUrl)
                    .put("enabled", config.enabled)
                    .put("qualities", qualities)
                    .put("requiresLossless", config.requiresLossless)
                    .put("requiresBitChordAuth", config.requiresBitChordAuth)
                    .apply { config.quality?.let { put("quality", it) } }
            )
        }
        store.edit()
            .putString(KEY_CONFIGS, array.toString())
            .apply()
    }
}

/** Turns an addon row into the same [Song] shape the rest of Music already plays. */
internal fun MusicAddonTrack.toSong(config: MusicAddonConfig): Song {
    val formatText = listOfNotNull(format, quality).joinToString(" ").lowercase()
    return Song(
        videoId = MusicAddonRegistry.trackKey(config.id, id),
        title = title,
        artist = artist.ifBlank { config.name },
        thumbnailUrl = artworkUrl,
        durationText = durationSeconds?.let(::addonDurationText),
        albumName = album,
        playbackSource = config.name,
        sourceQuality = when {
            "lossless" in formatText || "flac" in formatText || "alac" in formatText ||
                "wav" in formatText -> "LOSSLESS"
            "320" in formatText || "high" in formatText -> "HIGH"
            formatText.isNotBlank() -> "LOW"
            else -> null
        }
    )
}

private fun addonDurationText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainder = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainder)
    } else {
        "%d:%02d".format(minutes, remainder)
    }
}

/** Reads `true`/`false`, `1`/`0` or their string forms. */
private fun JSONObject.flag(key: String): Boolean {
    val value = opt(key) ?: return false
    if (value == JSONObject.NULL) return false
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1" || value.equals("yes", true)
        else -> false
    }
}

/** Accepts seconds as a number or `M:SS` / `H:MM:SS`, since addons send both. */
private fun JSONObject.durationSeconds(): Int? {
    val value = opt("duration") ?: return null
    if (value is Number) return value.toDouble().takeIf { it > 0 }?.toInt()
    if (value !is String) return null
    val text = value.trim()
    text.toDoubleOrNull()?.let { return it.takeIf { seconds -> seconds > 0 }?.toInt() }
    val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }?.takeIf { it > 0 }
}

/** Reads a JSON value as the query-string form the addon expects. */
private fun JSONObject.string(key: String): String {
    val value = opt(key) ?: return ""
    if (value == JSONObject.NULL) return ""
    return value.toString().trim().takeIf { it != "null" }.orEmpty()
}

private fun JSONObject.queryValue(key: String): String? {
    val value = opt(key) ?: return null
    if (value == JSONObject.NULL) return null
    return when (value) {
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
}
