package com.nuvio.tv.core.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-enables the Home accessibility service after boot or an app update, which can both reset it. */
class LauncherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> AccessibilityWatchdog.ensureEnabled(context)
        }
    }
}
