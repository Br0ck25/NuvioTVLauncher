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

    fun acceptKeyRelease(pressedAtMs: Long, isRelease: Boolean = true, isCanceled: Boolean = false): Boolean {
        if (!isRelease || isCanceled) return false
        val previous = lastKeyRelease
        if (previous != null && pressedAtMs - previous < HomeKeyGesture.DEFAULT_MIN_GAP_MS) return false
        lastKeyRelease = pressedAtMs
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
    }
}
