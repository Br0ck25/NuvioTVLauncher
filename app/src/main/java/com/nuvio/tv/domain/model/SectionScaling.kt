package com.nuvio.tv.domain.model

/**
 * Live TV fork: how large one top-level section draws its text and its interface.
 *
 * [textPercent] changes text only (it is applied on top of the system font scale).
 * [uiPercent] scales the whole page — spacing, cards and text together.
 * 100 means the app's normal size; Settings is never scaled so it always stays usable.
 *
 * Stored per profile with the other layout settings.
 */
data class SectionScale(
    val textPercent: Int = 100,
    val uiPercent: Int = 100
) {
    val textFactor: Float get() = textPercent / 100f
    val uiFactor: Float get() = uiPercent / 100f
}

/** The sections that can be scaled, and the limits of their settings. */
object SectionScales {
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val CALENDAR = "calendar"
    const val LIVE_TV = "livetv"
    const val SPORTS = "sports"
    const val ON_DEMAND = "ondemand"
    const val RECORDINGS = "recordings"
    const val YOUTUBE = "youtube"
    const val MUSIC = "music"

    val ALL = listOf(
        HOME, DISCOVER, SEARCH, LIBRARY, CALENDAR, LIVE_TV,
        SPORTS, ON_DEMAND, RECORDINGS, YOUTUBE, MUSIC
    )

    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 120
    const val STEP_PERCENT = 5

    /** The choices the settings rows offer, from smallest to largest. */
    val PERCENT_STEPS: List<Int> = (MIN_PERCENT..MAX_PERCENT step STEP_PERCENT).toList()

    /**
     * Music and OneTube were already drawn a little smaller than the rest of the app before
     * this setting existed, so their first run keeps exactly that size.
     */
    fun defaultFor(key: String): SectionScale = when (key) {
        MUSIC -> SectionScale(uiPercent = 88)
        YOUTUBE -> SectionScale(uiPercent = 90)
        else -> SectionScale()
    }

    fun defaults(): Map<String, SectionScale> = ALL.associateWith(::defaultFor)

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}
