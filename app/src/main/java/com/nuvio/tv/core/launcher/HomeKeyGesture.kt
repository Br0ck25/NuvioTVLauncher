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
 * Both entry points use it so the timing is identical whether the press was filtered by
 * [LauncherAccessibilityService] or delivered to the focused activity (see MainActivity). A press
 * is handled immediately as a single press - nothing waits for a possible second press - and the
 * double tap merely adds the profile switcher on top of the Home request that already happened.
 */
class HomeKeyGesture(
    private val minGapMs: Long = HomeKeyGesture.DEFAULT_MIN_GAP_MS,
    private val doubleTapWindowMs: Long = HomeKeyGesture.DEFAULT_DOUBLE_TAP_WINDOW_MS
) {
    private var lastPressAtMs = 0L

    fun onPress(nowMs: Long): HomeKeyPress {
        val sincePreviousPress = nowMs - lastPressAtMs
        lastPressAtMs = nowMs
        return when {
            sincePreviousPress < minGapMs -> HomeKeyPress.IGNORED
            sincePreviousPress <= doubleTapWindowMs -> HomeKeyPress.DOUBLE
            else -> HomeKeyPress.SINGLE
        }
    }

    companion object {
        /** Presses closer together than this are duplicate deliveries of one press, not a double tap. */
        const val DEFAULT_MIN_GAP_MS = 120L

        /** A second HOME press inside this window opens the profile switcher. */
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 450L
    }
}
