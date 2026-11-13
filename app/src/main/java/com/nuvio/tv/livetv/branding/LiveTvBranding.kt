package com.nuvio.tv.livetv.branding

/** "Nuvio Launcher" branding: the app name and tagline, and the logo shown on the splash screen. */
object LiveTvBranding {
    const val APP_NAME = "Nuvio Launcher"
    const val TAGLINE = "Live TV  ·  On Demand  ·  Streaming"

    /** The wordmark to show: this app's own logo, in the theme's colors (the launcher has no separate "+ IPTV" artwork). */
    fun iptvWordmark(nuvioWordmark: Int): Int = nuvioWordmark
}
