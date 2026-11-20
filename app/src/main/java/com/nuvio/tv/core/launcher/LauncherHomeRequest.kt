package com.nuvio.tv.core.launcher

import android.content.Intent

/**
 * Shared contract for "go back to the top of the Nuvio Home screen" requests.
 *
 * A Home request reaches [com.nuvio.tv.MainActivity] in two ways:
 * - [LauncherAccessibilityService] intercepts the device HOME key (it is consumed before the
 *   system can act on it) and re-delivers it as an explicit intent carrying [EXTRA_GO_HOME].
 * - When Nuvio is the default launcher and the service is disabled, the system delivers a plain
 *   ACTION_MAIN / CATEGORY_HOME intent instead, which is treated the same way.
 */
object LauncherHomeRequest {

    /** Marks an intent as an explicit "land on Home, scrolled to the top" request. */
    const val EXTRA_GO_HOME = "com.nuvio.tv.extra.GO_HOME"

    /** The remote's Apps button: taken by the accessibility service while another app is in front. */
    const val EXTRA_OPEN_APPS = "com.nuvio.tv.extra.OPEN_APPS"

    /** True when [intent] asks for the Apps page (the service's extra, or Android's own ALL_APPS intent). */
    fun isAppsRequest(intent: android.content.Intent?): Boolean =
        intent != null && (intent.getBooleanExtra(EXTRA_OPEN_APPS, false) || intent.action == android.content.Intent.ACTION_ALL_APPS)

    /** Uptime of the physical HOME release, so delivery latency cannot split a double tap. */
    const val EXTRA_HOME_PRESS_UPTIME_MS = "com.nuvio.tv.extra.HOME_PRESS_UPTIME_MS"

    /**
     * Marks an intent as an explicit "open the profile switcher" request, produced by double-tapping
     * HOME. Always travels together with [EXTRA_GO_HOME], so backing out of the switcher lands on Home.
     */
    const val EXTRA_OPEN_PROFILE_SELECTION = "com.nuvio.tv.extra.OPEN_PROFILE_SELECTION"

    /**
     * True when [intent] should take the user to the Home screen and reset its scroll position.
     * Plain HOME-category launches are included so the behaviour is identical with or without the
     * accessibility service.
     */
    fun isRequested(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_GO_HOME, false)) return true
        return intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
    }

    /**
     * Classify every HOME intent with the Activity's one detector. A cold native launch seeds the
     * detector (so the next press can be a double tap), but must not bypass startup/onboarding.
     */
    fun onIntent(
        intent: Intent?,
        gesture: HomeKeyGesture,
        nowMs: Long,
        fromNewIntent: Boolean = true
    ): HomeKeyPress {
        if (!isRequested(intent)) return HomeKeyPress.IGNORED
        val press = gesture.onPress(pressUptimeMs(intent, nowMs))
        if (!fromNewIntent && intent?.getBooleanExtra(EXTRA_GO_HOME, false) != true) {
            return HomeKeyPress.IGNORED
        }
        return press
    }

    /** Native HOME intents have no timestamp; accessibility requests carry the key's eventTime. */
    fun pressUptimeMs(intent: Intent?, nowMs: Long): Long {
        val timestamp = intent?.getLongExtra(EXTRA_HOME_PRESS_UPTIME_MS, nowMs) ?: nowMs
        return timestamp.takeIf { it in 0..nowMs } ?: nowMs
    }

    /** True when the user asked for the profile switcher by double-tapping HOME. */
    fun isProfileSelectionRequested(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_PROFILE_SELECTION, false) == true
}
