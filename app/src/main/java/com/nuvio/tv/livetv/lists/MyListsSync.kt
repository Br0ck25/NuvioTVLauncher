package com.nuvio.tv.livetv.lists

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A channel as mylistsaddon.com lists it for a viewer. */
internal data class SyncedChannelInfo(
    val id: String,
    val name: String,
    val catalogId: String,
    val logoUrl: String?,
    val order: Int,
)

/** An exact episode named by its id: the show (an IMDb or TMDB id), the season and the episode. */
internal data class ExactEpisode(val showId: String, val season: Int, val episode: Int)

/** What changing the synced channels from [SyncPlan.current] to the ones the server lists comes to. */
internal data class SyncPlan(
    val add: List<ListChannel>,
    val update: List<ListChannel>,
    /** Channels whose lineup moved to another catalog or addon address: their schedule starts again. */
    val sourceChanged: List<String>,
    val remove: List<String>,
) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && sourceChanged.isEmpty() && remove.isEmpty()
}

/**
 * Channels made on mylistsaddon.com, shown in Live TV for a viewer who has the My Lists addon installed.
 *
 * The addon's own address says whose it is (`mylistsaddon.com/<target>/tv/manifest.json`), and the server hands the
 * channels of that target out without a login, so nothing has to be linked here. Only a personal target counts: the
 * shared default one (what everybody who installs the generic addon gets) is never synced.
 *
 * What is pure (reading addresses and answers, planning the change) is kept apart from the loop that does the
 * asking, so it can be tested.
 */
internal object MyListsSync {
    const val ORIGIN = "https://mylistsaddon.com"
    /** Most items of one lineup that are used. */
    const val MAX_LINEUP_ITEMS = 300

    /** Targets that belong to everybody, or are not viewers at all. */
    private val SHARED_TARGETS = setOf("nuvio-default", "default", "bp", "api")
    private val EXACT_EPISODE = Regex("""^((?:tt\d+)|(?:tmdb:\d+)):(\d+):(\d+)$""")

    /**
     * The viewer's own target in an installed addon's address, or null when it is another host, the shared default
     * target, or not the TV addon (the address does not end in `/tv`).
     */
    fun personalTarget(addonUrl: String): String? {
        val uri = runCatching { URI(addonUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "mylistsaddon.com" && host != "www.mylistsaddon.com") return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }.toMutableList()
        if (segments.lastOrNull().equals("manifest.json", ignoreCase = true)) segments.removeAt(segments.lastIndex)
        if (!segments.lastOrNull().equals("tv", ignoreCase = true)) return null
        segments.removeAt(segments.lastIndex)
        val target = when {
            segments.size == 2 && (segments[0] == "u" || segments[0] == "i") -> segments[1]
            segments.size == 1 -> segments[0]
            else -> return null
        }
        return target.takeUnless { it.lowercase() in SHARED_TARGETS }
    }

    /** The addon's address without `manifest.json` or a trailing slash: where its catalogs are asked for. */
    fun addonBase(addonUrl: String): String = addonUrl.trim().removeSuffix("/manifest.json").trimEnd('/')

    /** The channels in the server's sync answer; empty for anything that is not a good answer. */
    fun parseChannels(json: String): List<SyncedChannelInfo> = runCatching {
        val root = JSONObject(json)
        if (!root.optBoolean("ok", false)) return@runCatching emptyList()
        val array = root.optJSONArray("channels") ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifBlank { return@mapNotNull null }
            val name = o.optString("name").trim().ifBlank { return@mapNotNull null }
            val catalog = o.optString("catalogId").trim().ifBlank { return@mapNotNull null }
            val logo = o.optString("logoUrl").ifBlank { o.optString("posterUrl") }.ifBlank { o.optString("poster") }
            SyncedChannelInfo(id, name, catalog, absolute(logo), o.optInt("playOrder", i + 1))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun absolute(url: String): String? = when {
        url.isBlank() -> null
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> ORIGIN + url
        else -> "$ORIGIN/$url"
    }

    /** The items of a catalog answer, in the order the server gave them. */
    fun parseLineup(json: String): List<ListItem> = runCatching {
        val metas = JSONObject(json).optJSONArray("metas") ?: return@runCatching emptyList()
        (0 until metas.length()).mapNotNull { i ->
            val o = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifBlank { return@mapNotNull null }
            val name = o.optString("name").trim()
            val image = o.optString("thumbnail").ifBlank { o.optString("poster") }.ifBlank { o.optString("background") }.ifBlank { null }
            // The server repeats the title as the description; that says nothing more.
            val description = o.optString("description").trim().ifBlank { null }?.takeIf { it != name }
            ListItem(id, o.optString("type").ifBlank { "series" }, name.ifBlank { id }, image, description)
        }.take(MAX_LINEUP_ITEMS)
    }.getOrDefault(emptyList())

    /** [id] as an exact episode (`tt0903747:1:2` or `tmdb:1396:1:2`), or null for a film or a whole show. */
    fun exactEpisode(id: String): ExactEpisode? {
        val m = EXACT_EPISODE.matchEntire(id.trim()) ?: return null
        val season = m.groupValues[2].toIntOrNull() ?: return null
        val episode = m.groupValues[3].toIntOrNull() ?: return null
        return ExactEpisode(m.groupValues[1], season, episode)
    }

    /** Turns what the server lists into list channels ([baseUrl]: the installed addon's address). */
    fun toChannels(infos: List<SyncedChannelInfo>, baseUrl: String): List<ListChannel> =
        infos.map { info ->
            ListChannel(
                id = MY_LISTS_CHANNEL_ID_PREFIX + info.id,
                name = info.name,
                refs = emptyList(),
                seed = 0L,
                synced = SyncedSource(baseUrl, info.catalogId, info.logoUrl, info.order),
            )
        }

    /** What it takes to make the synced channels in [current] the ones in [incoming]; the viewer's own channels are left alone. */
    fun plan(current: List<ListChannel>, incoming: List<ListChannel>): SyncPlan {
        val have = current.filter { it.isSynced }.associateBy { it.id }
        val want = incoming.filter { it.isSynced }.associateBy { it.id }
        val add = want.values.filter { it.id !in have }
        val changed = want.values.filter { new -> have[new.id]?.let { it != new } == true }
        val moved = changed.filter { new ->
            val old = have.getValue(new.id).synced!!
            old.baseUrl != new.synced!!.baseUrl || old.catalogId != new.synced.catalogId
        }.map { it.id }
        return SyncPlan(add = add, update = changed, sourceChanged = moved, remove = have.keys.filter { it !in want })
    }

    /** Asks for a channel's lineup. Null when the server could not be reached or had nothing for either catalog type. */
    suspend fun fetchLineup(client: OkHttpClient, source: SyncedSource): Pair<List<ListItem>, Int>? = withContext(Dispatchers.IO) {
        for (type in listOf("series", "tv")) {
            val body = get(client, "${source.baseUrl}/catalog/$type/${source.catalogId}.json") ?: continue
            val items = parseLineup(body)
            if (items.isNotEmpty()) return@withContext items to body.hashCode()
        }
        null
    }

    internal fun get(client: OkHttpClient, url: String, etag: String? = null): String? = try {
        val request = Request.Builder().url(url)
            .header("Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (error: java.io.IOException) {
        Log.d(TAG, "Could not reach $url: ${error.message}")
        null
    }

    internal const val TAG = "MyListsSync"
}

/**
 * Keeps the synced channels up to date while the app runs: asks the server every few minutes (and once when Live TV
 * starts), adds, renames and removes channels to match, and starts a channel's schedule again when its lineup was
 * edited. A change made on mylistsaddon.com shows up within one round.
 */
internal object MyListsChannelSync {
    private const val INTERVAL_MS = 5L * 60 * 1000
    private const val MAX_LINEUP_CHECKS = 20
    private const val PREFS = "mylists_channel_sync"
    private const val KEY_LIVE_TV_SHOWN = "live_tv_shown"

    private var job: Job? = null
    private val lineupPrints = ConcurrentHashMap<String, Int>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Not while Home is still loading.
            com.nuvio.tv.livetv.startup.AppSettled.awaitDrawn(maxWaitMs = 15_000)
            delay(5_000)
            while (isActive) {
                runCatching { syncOnce() }.onFailure { Log.w(MyListsSync.TAG, "Sync failed", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lineupPrints.clear()
    }

    private suspend fun syncOnce() {
        val entry = ListChannels.entryPoint() ?: return
        val context = ListChannels.appContextOrNull() ?: return
        val installed = runCatching { entry.addonRepository().getInstalledAddons().first() }.getOrDefault(emptyList())
        val mine = installed.firstNotNullOfOrNull { addon -> MyListsSync.personalTarget(addon.baseUrl)?.let { addon to it } }
        if (mine == null) {
            // The addon was removed (or only the shared one is installed): its channels go.
            ListChannels.applySynced(emptyList())
            return
        }
        val (addon, target) = mine
        val client = entry.okHttpClient()
        val url = "${MyListsSync.ORIGIN}/api/nuvio-tv/sync/" + URLEncoder.encode(target, "UTF-8").replace("+", "%20")
        val body = withContext(Dispatchers.IO) { MyListsSync.get(client, url) } ?: return
        val infos = MyListsSync.parseChannels(body)
        if (infos.isEmpty()) {
            ListChannels.applySynced(emptyList())
            return
        }
        showLiveTvOnce(entry, context)
        val channels = MyListsSync.toChannels(infos, MyListsSync.addonBase(addon.baseUrl))
        ListChannels.applySynced(channels)

        // A lineup edited on the server: that channel's schedule is built again from the new one.
        for (channel in channels.take(MAX_LINEUP_CHECKS)) {
            val source = channel.synced ?: continue
            val (_, print) = MyListsSync.fetchLineup(client, source) ?: continue
            val before = lineupPrints.put(channel.id, print)
            if (before != null && before != print) ListChannels.rebuildSchedule(channel.id)
        }
    }

    /**
     * Channels made on mylistsaddon.com are only useful where Live TV shows. A viewer who has them and has Live TV
     * hidden (Essential starts that way) gets it shown, once; switching it off again afterwards stays off.
     */
    private suspend fun showLiveTvOnce(entry: ListChannelsEntryPoint, context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LIVE_TV_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_LIVE_TV_SHOWN, true).apply()
        val live = entry.liveTvPreferences()
        if (!live.settings.first().showInSidebar) live.updateSettings { it.copy(showInSidebar = true) }
    }
}
