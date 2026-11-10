package com.nuvio.tv.core.launcher

import android.content.Context

internal data class LauncherSelfSetupStatus(
    val secureSettingsGranted: Boolean,
    val isDefaultHome: Boolean,
    val accessibilityEnabled: Boolean,
    val watchdogOptedIn: Boolean
)

/** Shared finishing step and status for the on-device setup flow. */
internal object LauncherSelfSetup {
    /** Call after the grants succeed: opts in to the watchdog and enables the service right away. */
    fun finish(context: Context) {
        if (!AccessibilityWatchdog.canWriteSecureSettings(context)) return
        AccessibilityWatchdog.setOptIn(context, true)
    }

    fun status(context: Context) = LauncherSelfSetupStatus(
        secureSettingsGranted = AccessibilityWatchdog.canWriteSecureSettings(context),
        isDefaultHome = LauncherSetup.isDefaultHome(context),
        accessibilityEnabled = LauncherAccessibilityService.isServiceEnabled(context),
        watchdogOptedIn = AccessibilityWatchdog.isOptedIn(context)
    )
}
