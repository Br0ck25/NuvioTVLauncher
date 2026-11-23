package com.nuvio.tv.music

import android.content.Context
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/** The queue as it was when the app last went away. */
data class RestoredMusicQueue(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val repeatMode: RepeatMode,
    val shuffle: Boolean
)

/**
 * Small persistent snapshot of the Music queue and playhead.
 *
 * The queue lives outside ExoPlayer so a process restart would otherwise lose it. This is
 * deliberately a SharedPreferences string rather than another database: it is one row,
 * written rarely, and read once when the player is first built.
 */
internal object MusicQueueStore {
    private const val PREFS = "nuvio_music_queue"
    private const val KEY_SNAPSHOT = "snapshot"

    fun save(context: Context, state: MusicPlayer.State) {
        if (state.queue.isEmpty() || state.index !in state.queue.indices) {
            clear(context)
            return
        }
        val json = JSONObject().apply {
            put("index", state.index)
            put("positionMs", state.positionMs.coerceAtLeast(0L))
            put("durationMs", state.durationMs.coerceAtLeast(0L))
            put("repeat", state.repeatMode.name)
            put("shuffle", state.shuffle)
            put(
                "songs",
                JSONArray().apply {
                    state.queue.forEach { song -> put(songJson(song)) }
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, json.toString())
            .apply()
    }

    fun restore(context: Context): RestoredMusicQueue? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null)
            ?: return null
        val json = JSONObject(raw)
        val songsJson = json.optJSONArray("songs") ?: return null
        val songs = buildList {
            for (index in 0 until songsJson.length()) {
                songFromJson(songsJson.optJSONObject(index))?.let(::add)
            }
        }
        if (songs.isEmpty()) return null
        val savedIndex = json.optInt("index", 0)
        val index = savedIndex.coerceIn(0, songs.lastIndex)
        val repeat = runCatching {
            RepeatMode.valueOf(json.optString("repeat", RepeatMode.OFF.name))
        }.getOrDefault(RepeatMode.OFF)
        RestoredMusicQueue(
            songs = songs,
            index = index,
            positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
            durationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L),
            repeatMode = repeat,
            shuffle = json.optBoolean("shuffle", false)
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNAPSHOT)
            .apply()
    }

    private fun songJson(song: Song): JSONObject = JSONObject().apply {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        put("thumbnailUrl", song.thumbnailUrl ?: JSONObject.NULL)
        put("durationText", song.durationText ?: JSONObject.NULL)
        put("artistId", song.artistId ?: JSONObject.NULL)
        put("albumId", song.albumId ?: JSONObject.NULL)
        put("albumName", song.albumName ?: JSONObject.NULL)
        put("isVideo", song.isVideo)
        put("isVideoOrigin", song.isVideoOrigin)
        put("localUri", song.localUri ?: JSONObject.NULL)
        put("localPath", song.localPath ?: JSONObject.NULL)
        put("downloadFormat", song.downloadFormat ?: JSONObject.NULL)
        put("trackNumber", song.trackNumber ?: JSONObject.NULL)
        put("radioName", song.radioName ?: JSONObject.NULL)
    }

    private fun songFromJson(json: JSONObject?): Song? {
        if (json == null) return null
        val videoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        return Song(
            videoId = videoId,
            title = json.optString("title", videoId),
            artist = json.optString("artist"),
            thumbnailUrl = json.stringOrNull("thumbnailUrl"),
            durationText = json.stringOrNull("durationText"),
            artistId = json.stringOrNull("artistId"),
            albumId = json.stringOrNull("albumId"),
            albumName = json.stringOrNull("albumName"),
            isVideo = json.optBoolean("isVideo", false),
            isVideoOrigin = json.optBoolean("isVideoOrigin", false),
            localUri = json.stringOrNull("localUri"),
            localPath = json.stringOrNull("localPath"),
            downloadFormat = json.stringOrNull("downloadFormat"),
            trackNumber = if (json.isNull("trackNumber")) null else json.optInt("trackNumber"),
            radioName = json.stringOrNull("radioName")
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
