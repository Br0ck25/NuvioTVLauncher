package com.nuvio.tv.core.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.MainActivity

private const val TAG = "LauncherBootReceiver"

/** Re-enables the Home accessibility service after boot or an app update, and launches Nuvio on boot. */
class LauncherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val accessibilityEnabled = AccessibilityWatchdog.ensureEnabled(context)
                val defaultHome = LauncherSetup.isDefaultHome(context)
                val fallbackAllowed = LauncherSetup.accessibilityFallbackAllowed(context)
                Log.i(
                    TAG,
                    "${intent.action}: watchdogEnabledService=$accessibilityEnabled " +
                        "canWriteSecure=${AccessibilityWatchdog.canWriteSecureSettings(context)} " +
                        "optedIn=${AccessibilityWatchdog.isOptedIn(context)} " +
                        "defaultHome=$defaultHome fallbackAllowed=$fallbackAllowed"
                )
                if (fallbackAllowed || defaultHome) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    runCatching { context.startActivity(launchIntent) }
                        .onSuccess { Log.i(TAG, "Home relaunch requested (may still be blocked by the system; check ActivityTaskManager)") }
                        .onFailure { Log.w(TAG, "Home relaunch failed", it) }
                } else {
                    Log.i(TAG, "Skipping home relaunch: not default home and accessibility fallback disallowed")
                }
            }
        }
    }
}
