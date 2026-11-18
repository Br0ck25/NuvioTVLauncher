package com.nuvio.tv.livetv.branding

/**
 * Nuvio One branding. The fork's own logo images sit next to Nuvio's (they don't replace
 * them), so Nuvio's artwork stays untouched and future Nuvio updates merge cleanly.
 */
object LiveTvBranding {
    const val APP_NAME = "Nuvio Launcher"
    const val TAGLINE = "All your entertainment. One app."

    /** The launcher's own wordmark (already themed), shown on the splash and in Live TV settings. */
    fun iptvWordmark(nuvioWordmark: Int): Int = nuvioWordmark
}
