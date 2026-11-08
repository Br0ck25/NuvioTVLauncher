package com.nuvio.tv.core.launcher

internal enum class HomeMechanism { NATIVE, ACCESSIBILITY, UNAVAILABLE }
internal enum class NativeHomeRestriction { NONE_OBSERVED, ROLE_RESOLVER_MISMATCH, REQUEST_FAILED, UNKNOWN }

/** Pure policy shared by startup and capability reporting; never infers support from install source. */
internal object HomeCapabilityPolicy {
    fun mechanism(defaultHome: Boolean, enabled: Boolean, operational: Boolean, fallbackAllowed: Boolean = true): HomeMechanism = when {
        defaultHome -> HomeMechanism.NATIVE
        enabled && operational && fallbackAllowed -> HomeMechanism.ACCESSIBILITY
        else -> HomeMechanism.UNAVAILABLE
    }

    fun shouldRequestNativeHome(
        attempted: Boolean,
        defaultHome: Boolean,
        roleAvailable: Boolean,
        roleHeld: Boolean,
        accessibilityEnabled: Boolean
    ): Boolean = !attempted && !defaultHome && roleAvailable && !roleHeld && !accessibilityEnabled
}

/**
 * Service-side delivery guard, not a second single/double-tap classifier. Physical releases retain
 * their uptime and are still classified by MainActivity's existing HomeKeyGesture.
 */
internal class HomeFallbackPolicy {
    private var lastKeyRelease: Long? = null
    private var lastLaunchAt: Long? = null
    private var launcherWindowHandled = false
    private var rechecksLeft = 0

    fun acceptKeyRelease(pressedAtMs: Long, isRelease: Boolean = true, isCanceled: Boolean = false): Boolean {
        if (!isRelease || isCanceled) return false
        val previous = lastKeyRelease
        if (previous != null && pressedAtMs - previous < HomeKeyGesture.DEFAULT_MIN_GAP_MS) return false
        lastKeyRelease = pressedAtMs
        rechecksLeft = MAX_RECHECKS
        return true
    }

    /**
     * The system also starts the stock launcher for every HOME press, and its start can land after
     * ours (seen on Chromecast with Google TV when two presses arrive within the double-tap window).
     * A launcher window right after our own launch is suppressed by [acceptLauncherWindow]; this says
     * whether a single follow-up check is still warranted. Only physical presses grant a budget, so a
     * stock launcher that keeps re-asserting itself can cause at most [MAX_RECHECKS] extra launches.
     */
    fun shouldScheduleRecheck(nowMs: Long): Boolean {
        if (rechecksLeft <= 0) return false
        val previous = lastLaunchAt ?: return false
        return nowMs - previous in 0..RECENT_LAUNCH_MS
    }

    /** Spends one re-check; false when none are left for the current press. */
    fun consumeRecheck(): Boolean {
        if (rechecksLeft <= 0) return false
        rechecksLeft--
        return true
    }

    fun onOtherApplicationWindow() {
        launcherWindowHandled = false
    }

    fun acceptLauncherWindow(nowMs: Long): Boolean {
        if (launcherWindowHandled) return false
        // Latch even a suppressed event, so later duplicate window events cannot become a fake tap.
        launcherWindowHandled = true
        val previous = lastLaunchAt
        return previous == null || nowMs - previous > WINDOW_DEBOUNCE_MS
    }

    fun onLaunch(nowMs: Long) {
        lastLaunchAt = nowMs
    }

    companion object {
        const val WINDOW_DEBOUNCE_MS = 400L
        const val RECENT_LAUNCH_MS = 1_000L
        const val RECHECK_DELAY_MS = 600L
        const val MAX_RECHECKS = 2
    }
}
