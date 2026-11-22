package com.nuvio.tv.music

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.withAppLocale
import fi.iki.elonen.NanoHTTPD
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A song waiting for the host to approve it. */
data class PartyPendingRequest(
    val id: String,
    val song: Song,
    val guestId: String,
    val guestName: String
)

/** The state the TV and the guest page need to know about the party. */
data class MusicPartyState(
    val running: Boolean = false,
    val url: String? = null,
    val locked: Boolean = false,
    val additions: Int = 0,
    val requireApproval: Boolean = false,
    val pending: List<PartyPendingRequest> = emptyList(),
    val error: String? = null
)

/**
 * Hosts a small LAN web page for Party Mode.
 *
 * The TV owns the YouTube Music session and the queue. Phones only send a video id;
 * everything else is looked up on this device, so guests never need an account or an app.
 */
object MusicPartyManager {
    private const val TAG = "MusicParty"

    private val _state = MutableStateFlow(MusicPartyState())
    val state: StateFlow<MusicPartyState> = _state.asStateFlow()

    private var server: MusicPartyServer? = null
    private var appContext: Context? = null

    /** Songs added from phones this session, keyed by video id. */
    private val addedBy = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val owners = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val partySongs = Collections.synchronizedSet(HashSet<String>())
    private val pending = Collections.synchronizedMap(LinkedHashMap<String, PartyPendingRequest>())

    val isLocked: Boolean get() = _state.value.locked
    val needsApproval: Boolean get() = _state.value.requireApproval

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        appContext = app
        MusicPlayer.init(app)
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val candidate = MusicPartyServer(app, token)
        runCatching {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = candidate
            addedBy.clear()
            owners.clear()
            partySongs.clear()
            pending.clear()
            val ip = DeviceIpAddress.get(app)
            if (ip.isNullOrBlank()) {
                _state.value = MusicPartyState(
                    running = true,
                    error = app.withAppLocale().getString(R.string.music_no_network)
                )
            } else {
                val url = "http://$ip:${candidate.listeningPort}/party/$token/"
                Log.i(TAG, "Party URL: $url")
                _state.value = MusicPartyState(running = true, url = url)
            }
            MusicPartyService.start(app)
        }.onFailure {
            Log.w(TAG, "Could not start party server: ${it.message}")
            runCatching { candidate.stop() }
            _state.value = MusicPartyState(error = it.message ?: "Couldn't start the party.")
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
        server = null
        addedBy.clear()
        owners.clear()
        partySongs.clear()
        pending.clear()
        _state.value = MusicPartyState()
        appContext?.let { MusicPartyService.stop(it) }
    }

    fun setLocked(locked: Boolean) {
        _state.value = _state.value.copy(locked = locked)
    }

    fun setRequireApproval(required: Boolean) {
        _state.value = _state.value.copy(requireApproval = required)
    }

    /** Called from the phone server thread; the request waits on the TV. */
    internal fun submitPending(song: Song, guestId: String, guestName: String): PartyPendingRequest {
        val request = PartyPendingRequest(
            id = java.util.UUID.randomUUID().toString().take(12),
            song = song,
            guestId = guestId,
            guestName = guestName.take(24)
        )
        pending[request.id] = request
        _state.value = _state.value.copy(pending = pending.values.toList())
        return request
    }

    /** Called from the TV party screen, on the main thread. */
    fun approvePending(id: String) {
        val request = pending.remove(id) ?: return
        val result = MusicPlayer.enqueue(request.song)
        if (result == QueueAddResult.ADDED || result == QueueAddResult.STARTED) {
            recordAddition(request.song.videoId, request.guestName, request.guestId)
        }
        _state.value = _state.value.copy(pending = pending.values.toList())
    }

    fun rejectPending(id: String) {
        pending.remove(id)
        _state.value = _state.value.copy(pending = pending.values.toList())
    }

    internal fun recordAddition(videoId: String, name: String, guestId: String) {
        partySongs.add(videoId)
        if (name.isNotBlank()) addedBy[videoId] = name
        if (guestId.isNotBlank()) owners[videoId] = guestId
        _state.value = _state.value.copy(additions = partySongs.size)
    }

    internal fun forgetAddition(videoId: String) {
        partySongs.remove(videoId)
        addedBy.remove(videoId)
        owners.remove(videoId)
        _state.value = _state.value.copy(additions = partySongs.size)
    }

    internal fun clearAdditions() {
        partySongs.clear()
        addedBy.clear()
        owners.clear()
        _state.value = _state.value.copy(additions = 0)
    }

    fun addedBy(videoId: String): String? = addedBy[videoId]

    internal fun ownerOf(videoId: String): String? = owners[videoId]
}

private class MusicPartyServer(
    private val context: Context,
    private val token: String
) : NanoHTTPD(0) {

    private companion object {
        const val SKIP_VOTES_NEEDED = 2
    }

    private val pageHtml: String by lazy {
        context.assets.open("party/index.html").bufferedReader().use { it.readText() }
    }

    /** Search results the guest page is allowed to add, so the client cannot invent metadata. */
    private val foundSongs = Collections.synchronizedMap(
        object : LinkedHashMap<String, Song>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean =
                size > 300
        }
    )

    /** One vote per phone, reset whenever the track changes. */
    private val skipVoters = Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile
    private var voteSongId: String? = null

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/party/$token"
        if (!session.uri.startsWith(prefix)) return forbidden()
        val path = session.uri.removePrefix(prefix).trim('/')
        return runCatching {
            val guestId = session.parameters["guestId"]?.firstOrNull()?.take(64)
            when {
                path.isEmpty() -> page()
                path == "api/state" -> json(stateJson(guestId))
                path == "api/search" -> json(searchJson(session.parameters["q"]?.firstOrNull()))
                path == "api/add" && session.method == Method.POST -> json(addJson(session))
                path == "api/remove" && session.method == Method.POST -> json(removeJson(session))
                path == "api/vote" && session.method == Method.POST -> json(voteJson(session))
                else -> notFound()
            }
        }.getOrElse {
            Log.w("MusicParty", "request ${session.uri} failed: ${it.message}")
            json(JSONObject().put("ok", false).put("status", "error"), NanoHTTPD.Response.Status.INTERNAL_ERROR)
        }
    }

    private fun page(): Response = newFixedLengthResponse(
        Response.Status.OK,
        "text/html; charset=utf-8",
        pageHtml
    ).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun json(
        body: JSONObject,
        status: Response.Status = Response.Status.OK
    ): Response = newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        body.toString()
    ).apply { addHeader("Cache-Control", "no-store") }

    private fun stateJson(guestId: String?): JSONObject {
        val s = MusicPlayer.state.value
        val current = s.queue.getOrNull(s.index) ?: s.song
        syncVotes(current?.videoId)
        val upNext = if (s.queue.isEmpty() || s.index < 0) {
            emptyList()
        } else {
            s.queue.drop(s.index + 1).take(40)
        }
        return JSONObject().apply {
            put("running", true)
            put("locked", MusicPartyManager.isLocked)
            put("requireApproval", MusicPartyManager.needsApproval)
            put("pendingCount", MusicPartyManager.state.value.pending.size)
            put("device", Build.MODEL?.takeIf { it.isNotBlank() } ?: "this TV")
            put("additions", MusicPartyManager.state.value.additions)
            put("now", current?.let { songJson(it, guestId) } ?: JSONObject.NULL)
            put("positionMs", s.positionMs)
            put(
                "durationMs",
                if (s.durationMs > 0L) s.durationMs else current?.durationMillis() ?: 0L
            )
            put("isPlaying", s.isPlaying)
            put("skipVotes", skipVoters.size)
            put("skipVotesNeeded", SKIP_VOTES_NEEDED)
            put("voted", guestId != null && skipVoters.contains(guestId))
            put(
                "queue",
                JSONArray().apply {
                    upNext.forEach { put(songJson(it, guestId)) }
                }
            )
        }
    }

    private fun songJson(song: Song, guestId: String? = null): JSONObject = JSONObject().apply {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.albumName ?: "")
        put("thumb", song.thumbnailUrl?.artworkAt(480) ?: "")
        put("durationText", song.durationText ?: "")
        put("addedBy", MusicPartyManager.addedBy(song.videoId) ?: "")
        put(
            "mine",
            guestId != null && guestId.isNotBlank() &&
                MusicPartyManager.ownerOf(song.videoId) == guestId
        )
    }

    private fun syncVotes(currentId: String?) {
        if (currentId == null) {
            voteSongId = null
            skipVoters.clear()
            return
        }
        if (voteSongId != currentId) {
            voteSongId = currentId
            skipVoters.clear()
        }
    }

    private fun searchJson(query: String?): JSONObject {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            return JSONObject().put("ok", true).put("songs", JSONArray())
        }
        val found = runCatching {
            runBlocking {
                withTimeoutOrNull(15_000L) {
                    YtMusicRepository.search(q, SearchFilter.SONGS)
                }
            }
        }.getOrNull()?.getOrNull()
        val songs = found.orEmpty()
            .mapNotNull { result ->
                when (result) {
                    is SearchResult.TopTrack -> result.song
                    is SearchResult.Track -> result.song
                    else -> null
                }
            }
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
            .take(24)
        songs.forEach { foundSongs[it.videoId] = it }
        return JSONObject().apply {
            put("ok", true)
            put(
                "songs",
                JSONArray().apply { songs.forEach { put(songJson(it)) } }
            )
        }
    }

    private fun addJson(session: IHTTPSession): JSONObject {
        val payload = payload(session)
        val videoId = payload.optString("videoId")
            .ifBlank { session.parameters["videoId"]?.firstOrNull().orEmpty() }
        val guestId = payload.optString("guestId").take(64)
        val guestName = payload.optString("name").trim().take(24)
        if (MusicPartyManager.isLocked) {
            return JSONObject().put("ok", false).put("status", "locked")
        }
        val song = foundSongs[videoId]
            ?: return JSONObject().put("ok", false).put("status", "unknown")
        if (MusicPartyManager.needsApproval) {
            val request = MusicPartyManager.submitPending(song, guestId, guestName)
            return JSONObject().put("ok", true).put("status", "pending").put("id", request.id)
        }
        val result = enqueueOnMain(song)
            ?: return JSONObject().put("ok", false).put("status", "timeout")
        return when (result) {
            QueueAddResult.ADDED, QueueAddResult.STARTED -> {
                MusicPartyManager.recordAddition(song.videoId, guestName, guestId)
                JSONObject().put("ok", true).put(
                    "status",
                    if (result == QueueAddResult.STARTED) "started" else "added"
                )
            }
            QueueAddResult.ALREADY_QUEUED ->
                JSONObject().put("ok", true).put("status", "duplicate")
            QueueAddResult.INVALID ->
                JSONObject().put("ok", false).put("status", "unknown")
        }
    }

    /** A guest may remove a song they added, while it is still upcoming. */
    private fun removeJson(session: IHTTPSession): JSONObject {
        val payload = payload(session)
        val videoId = payload.optString("videoId")
            .ifBlank { session.parameters["videoId"]?.firstOrNull().orEmpty() }
        val guestId = payload.optString("guestId").take(64)
        if (guestId.isBlank() || MusicPartyManager.ownerOf(videoId) != guestId) {
            return JSONObject().put("ok", false).put("status", "denied")
        }
        val removed = runOnMain { MusicPlayer.removeUpcoming(videoId) } ?: false
        if (!removed) {
            return JSONObject().put("ok", false).put("status", "not_removable")
        }
        MusicPartyManager.forgetAddition(videoId)
        return JSONObject().put("ok", true).put("status", "removed")
    }

    /** One phone, one vote. Two votes skip the current track. */
    private fun voteJson(session: IHTTPSession): JSONObject {
        val payload = payload(session)
        val guestId = payload.optString("guestId").take(64)
        if (guestId.isBlank()) {
            return JSONObject().put("ok", false).put("status", "invalid")
        }
        val s = MusicPlayer.state.value
        val current = s.queue.getOrNull(s.index) ?: s.song
            ?: return JSONObject().put("ok", false).put("status", "no_track")
        syncVotes(current.videoId)
        if (!skipVoters.add(guestId)) {
            return JSONObject().put("ok", true)
                .put("status", "already")
                .put("votes", skipVoters.size)
                .put("needed", SKIP_VOTES_NEEDED)
        }
        val votes = skipVoters.size
        val skipped = if (votes >= SKIP_VOTES_NEEDED) {
            val didSkip = runOnMain { MusicPlayer.next(); true } ?: false
            if (didSkip) skipVoters.clear()
            didSkip
        } else {
            false
        }
        return JSONObject().put("ok", true)
            .put("status", if (skipped) "skipped" else "voted")
            .put("votes", votes)
            .put("needed", SKIP_VOTES_NEEDED)
    }

    private fun payload(session: IHTTPSession): JSONObject {
        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrDefault("")
        return runCatching { JSONObject(body) }.getOrDefault(JSONObject())
    }

    /** ExoPlayer must be touched on the main looper; the HTTP request runs on a NanoHTTPD thread. */
    private fun enqueueOnMain(song: Song): QueueAddResult? {
        val latch = CountDownLatch(1)
        var result: QueueAddResult? = null
        Handler(Looper.getMainLooper()).post {
            result = MusicPlayer.enqueue(song)
            latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return result
    }

    private fun runOnMain(block: () -> Boolean): Boolean? {
        val latch = CountDownLatch(1)
        var result: Boolean? = null
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block).getOrDefault(false)
            latch.countDown()
        }
        return if (latch.await(4, TimeUnit.SECONDS)) result else null
    }

    private fun forbidden(): Response = newFixedLengthResponse(
        Response.Status.FORBIDDEN,
        "text/plain; charset=utf-8",
        "This party link is not valid."
    )

    private fun notFound(): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        "Not found."
    )
}
