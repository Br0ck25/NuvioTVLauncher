package com.nuvio.tv.music

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenreSection
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CachedMusicFeed(
    val home: List<HomeShelf> = emptyList(),
    val explore: List<MoodGenreSection> = emptyList(),
    val account: Boolean = false,
    val savedAt: Long = 0L
)

/**
 * The last Music home/Explore feed, kept on disk so opening the Music section shows playlists
 * and shelves immediately while the network refresh runs behind them.
 */
internal object MusicFeedCache {
    private const val FILE_NAME = "music_feed_cache.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): CachedMusicFeed? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        json.decodeFromString<CachedMusicFeed>(file.readText())
    }.getOrNull()

    fun save(
        context: Context,
        home: List<HomeShelf>,
        explore: List<MoodGenreSection>,
        account: Boolean
    ) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val data = CachedMusicFeed(
                home = home,
                explore = explore,
                account = account,
                savedAt = System.currentTimeMillis()
            )
            file.writeText(json.encodeToString(data))
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
