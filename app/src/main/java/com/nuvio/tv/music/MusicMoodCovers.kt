package com.nuvio.tv.music

import android.content.Context

/**
 * Mood/genre tile covers, remembered for a week.
 *
 * The tile only asks for a cover when it is actually on screen, and the repository
 * already caches the shelves for the session. This keeps the cover across app
 * restarts so opening Explore does not refetch the same tiles every time.
 */
internal object MusicMoodCovers {
    private const val PREFS_NAME = "nuvio_music_mood_covers"
    private const val TTL_MS = 7L * 24L * 60L * 60L * 1000L

    fun get(context: Context, browseId: String, params: String?): String? = runCatching {
        val raw = prefs(context).getString(key(browseId, params), null) ?: return null
        val separator = raw.indexOf('|')
        if (separator <= 0) return null
        val savedAt = raw.substring(0, separator).toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > TTL_MS) return null
        raw.substring(separator + 1).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun put(context: Context, browseId: String, params: String?, url: String) = runCatching {
        if (url.isBlank()) return@runCatching
        prefs(context).edit()
            .putString(key(browseId, params), "${System.currentTimeMillis()}|$url")
            .apply()
    }

    private fun key(browseId: String, params: String?): String =
        "$browseId|${params.orEmpty()}"

    private fun prefs(context: Context) =
        com.nuvio.tv.core.profile.ActiveProfile.prefs(context.applicationContext, PREFS_NAME)
}
