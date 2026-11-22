package com.nuvio.tv.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Works out which translated caption line is showing at a given moment in a YouTube video.
 *
 * The player accepts the translated caption track this app serves but never turns its segments into
 * cues, so nothing would be drawn. The lines are read from the same local server and handed to the
 * subtitle overlay by the player screen instead. Only YouTube uses this: Live TV, On Demand and
 * addon subtitles keep the normal path untouched.
 */
internal class YouTubeTranslatedCaptions(
    private val videoId: String,
    private val language: String
) {
    private var segmentNumber = -1L
    private var segmentLoaded = false
    private var lastFetchMs = 0L
    private var cues: List<Cue> = emptyList()
    private var wholeFileCues: List<Cue>? = null
    private var wholeFileTried = false

    private class Cue(val startMs: Long, var endMs: Long, val text: String)

    /** The line showing at [positionMs], or null when nothing is said then. */
    suspend fun lineAt(positionMs: Long): String? = withContext(Dispatchers.IO) {
        if (!wholeFileTried) {
            wholeFileTried = true
            val file = YouTubeMpdServer.translatedCaptionFile(videoId, language)
            val cleaned = file?.let {
                if (YouTubeAutoCaptions.looksAutomatic(it)) YouTubeAutoCaptions.tidy(it) else it
            }
            val parsed = cleaned?.let(::parse).orEmpty()
            if (parsed.isNotEmpty()) {
                wholeFileCues = parsed
                Log.i(
                    TAG,
                    "direct translated captions $videoId/$language cues=${parsed.size} " +
                        "lastEnd=${parsed.last().endMs}ms"
                )
            } else {
                Log.i(TAG, "direct translated captions $videoId/$language unavailable; using local translation")
            }
        }
        wholeFileCues?.let { all ->
            val line = all.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }?.text
            if (line != null) return@withContext line
            // YouTube sometimes hands back a translated file that stops early. Once playback
            // moves past its last line, use the locally generated segments instead of going blank.
            val lastEnd = all.lastOrNull()?.endMs ?: 0L
            if (positionMs <= lastEnd + WHOLE_FILE_GRACE_MS) return@withContext null
            Log.i(TAG, "direct translated captions $videoId/$language stop at ${lastEnd}ms; using local translation")
            wholeFileCues = null
        }

        val number = positionMs / SEGMENT_MS
        val now = System.currentTimeMillis()
        // Retry a failed segment, but never more than once every couple of seconds.
        if ((number != segmentNumber || !segmentLoaded) && now - lastFetchMs >= SEGMENT_RETRY_MS) {
            val fetched = fetch(number)
            lastFetchMs = now
            if (fetched != null) {
                segmentNumber = number
                segmentLoaded = true
                cues = fetched
            }
        }
        if (segmentNumber != number) return@withContext null
        val inside = positionMs - number * SEGMENT_MS
        cues.firstOrNull { inside >= it.startMs && inside < it.endMs }?.text
    }

    /** Returns null when the segment could not be fetched, so the caller can retry it. */
    private fun fetch(number: Long): List<Cue>? {
        val url = YouTubeMpdServer.segmentUrl(videoId, language, number) ?: return null
        val body = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        }.getOrNull() ?: return null
        return parse(body)
    }

    private fun parse(vtt: String): List<Cue> {
        val cues = ArrayList<Cue>()
        var previousText: String? = null
        for (block in vtt.replace("\r\n", "\n").split(Regex("\n{2,}"))) {
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val match = timing.find(lines[timingIndex]) ?: continue
            fun part(i: Int) = match.groupValues[i].toLongOrNull() ?: 0L
            val start = part(1) * 3_600_000 + part(2) * 60_000 + part(3) * 1_000 + part(4)
            val end = part(5) * 3_600_000 + part(6) * 60_000 + part(7) * 1_000 + part(8)
            val rawText = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (rawText.isEmpty()) continue
            // Translated captions can still roll up (old line above the new one) and some cues
            // contain the same line twice. Keep one copy so the overlay cannot draw it twice.
            val text = YouTubeAutoCaptions.cleanRollingCue(rawText, previousText)
            if (text.isEmpty()) continue
            val last = cues.lastOrNull()
            if (last != null && last.text == text && start <= last.endMs + 500) {
                last.endMs = maxOf(last.endMs, end)
                previousText = text
                continue
            }
            // A translated file can keep the old cue alive while the next one starts. End it at
            // the next cue so two lines cannot be on screen together.
            if (last != null && start > last.startMs && start < last.endMs) {
                last.endMs = start
            }
            cues += Cue(start, end, text)
            previousText = text
        }
        return cues
    }

    private companion object {
        const val TAG = "YouTubeCaptions"
        const val SEGMENT_MS = YouTubeCaptionSegments.DURATION_SECONDS * 1_000
        /** A whole translated file that ends this close to the position is treated as finished. */
        const val WHOLE_FILE_GRACE_MS = 2_000L
        /** How soon a failed local segment is tried again. */
        const val SEGMENT_RETRY_MS = 2_000L
        /** Hours are optional in the timing line; minutes and seconds are always there. */
        val timing = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")
        val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    }
}
