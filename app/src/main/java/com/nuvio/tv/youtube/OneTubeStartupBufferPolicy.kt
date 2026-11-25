package com.nuvio.tv.youtube

/** Avoids making OneTube VOD playback wait on the general-purpose player's larger startup buffer. */
internal object OneTubeStartupBufferPolicy {
    const val MAX_INITIAL_BUFFER_MS = 2_500

    fun initialBufferMs(configuredMs: Int): Int =
        configuredMs.coerceAtLeast(1).coerceAtMost(MAX_INITIAL_BUFFER_MS)
}
