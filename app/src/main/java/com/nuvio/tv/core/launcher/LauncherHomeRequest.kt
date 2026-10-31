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
}
