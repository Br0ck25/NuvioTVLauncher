package com.nuvio.tv.core.launcher

/** What a HOME key press turned out to be. */
enum class HomeKeyPress {
    /** A duplicate delivery of the press we already handled. */
    IGNORED,

    /** A plain HOME press: go back to the top of Home. */
    SINGLE,

    /** A second press right after the first: also open the profile switcher. */
    DOUBLE
}

/**
 * Shared single/double tap detection for the HOME key.
 *
 * MainActivity owns one detector for raw keys, native HOME intents, and timestamped presses
 * forwarded by [LauncherAccessibilityService]. A press
 * is handled immediately as a single press - nothing waits for a possible second press - and the
 * double tap merely adds the profile switcher on top of the Home request that already happened.
 */
class HomeKeyGesture(
    private val minGapMs: Long = HomeKeyGesture.DEFAULT_MIN_GAP_MS,
    private val doubleTapWindowMs: Long = HomeKeyGesture.DEFAULT_DOUBLE_TAP_WINDOW_MS
) {
    private var lastPressAtMs: Long? = null

    fun onPress(nowMs: Long): HomeKeyPress {
        val previousPressAtMs = lastPressAtMs
        if (previousPressAtMs != null) {
            val sincePreviousPress = nowMs - previousPressAtMs
            // A duplicate (or out-of-order delivery) must not move the accepted press's clock.
            if (sincePreviousPress < minGapMs) return HomeKeyPress.IGNORED
            lastPressAtMs = nowMs
            if (sincePreviousPress <= doubleTapWindowMs) return HomeKeyPress.DOUBLE
        } else {
            lastPressAtMs = nowMs
        }
        return HomeKeyPress.SINGLE
    }

    companion object {
        /** Presses closer together than this are duplicate deliveries of one press, not a double tap. */
        const val DEFAULT_MIN_GAP_MS = 120L

        /** A second HOME press inside this window opens the profile switcher. */
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 450L
    }
}
