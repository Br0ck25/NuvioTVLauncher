package com.nuvio.tv.music

import com.music.bitchord.data.lyrics.BetterLyrics
import com.music.bitchord.data.lyrics.BiniLyrics
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.YouTubeMusicLyrics
import com.music.bitchord.data.lyrics.YouTubeTranscriptLyrics
import com.music.bitchord.data.lyrics.artistForLyricsSearch
import com.music.bitchord.data.lyrics.forLyricsSearch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics for a track, trying BitChord's word-synced providers first and falling back to
 * line-synced LRCLIB and YouTube.
 *
 * BiniLyrics and BetterLyrics return Apple Music TTML with per-word timing; LRCLIB is
 * line-synced; YouTube Music's Lyrics tab and transcript are the last resort for tracks the
 * catalogues don't have. All providers are asked concurrently, but answers are taken in that
 * priority order.
 */
object MusicLyricsRepository {
    data class Lyrics(
        val source: String,
        val lines: List<LyricLine>
    ) {
        val hasTiming: Boolean get() = lines.any { it.timeMs > 0L }
        val wordSynced: Boolean get() = lines.any { it.isWordSynced }
    }

    private const val PROVIDER_TIMEOUT_MS = 5_000L
    private const val YOUTUBE_TIMEOUT_MS = 4_000L

    private val cache = ConcurrentHashMap<String, Lyrics>()

    fun cached(videoId: String): Lyrics? = cache[videoId]

    suspend fun lyrics(song: Song): Lyrics? {
        cache[song.videoId]?.let { return it }
        val title = song.title.forLyricsSearch()
        val artist = song.artist.artistForLyricsSearch()
        val durationMs = song.durationMillis()
        val album = song.albumName

        return coroutineScope {
            val bini = async {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    runCatching { BiniLyrics.lyrics(title, artist, durationMs, album) }.getOrNull()?.lines
                }
            }
            val better = async {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    runCatching { BetterLyrics.lyrics(title, artist, durationMs, album) }.getOrNull()
                }
            }
            val lrcLib = async {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    runCatching { LrcLib.lyrics(title, artist, durationMs) }.getOrNull()
                }
            }
            val youtubeMusic = async {
                withTimeoutOrNull(YOUTUBE_TIMEOUT_MS) {
                    runCatching { YouTubeMusicLyrics.lyrics(song.videoId) }.getOrNull()
                }
            }
            val youtubeTranscript = async {
                withTimeoutOrNull(YOUTUBE_TIMEOUT_MS) {
                    runCatching { YouTubeTranscriptLyrics.lyrics(song.videoId) }.getOrNull()
                }
            }

            fun found(lines: List<LyricLine>?, source: String): Lyrics? {
                val usable = lines?.takeIf { list ->
                    list.any { it.text.isNotBlank() }
                } ?: return null
                return Lyrics(source, usable).also { cache[song.videoId] = it }
            }

            val biniLines = bini.await()
            if (biniLines?.any { it.isWordSynced } == true) {
                return@coroutineScope found(biniLines, "Apple Music · BiniLyrics")
            }
            val betterLines = better.await()
            if (betterLines?.any { it.isWordSynced } == true) {
                return@coroutineScope found(betterLines, "BetterLyrics")
            }
            found(biniLines, "Apple Music · BiniLyrics")?.let { return@coroutineScope it }
            found(betterLines, "BetterLyrics")?.let { return@coroutineScope it }
            found(lrcLib.await(), "LRCLIB")?.let { return@coroutineScope it }
            found(youtubeTranscript.await(), "Captions")?.let { return@coroutineScope it }
            found(youtubeMusic.await(), "Music catalogue")?.let { return@coroutineScope it }
            null
        }
    }
}
