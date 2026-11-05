package com.nuvio.tv.core.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nuvio.tv.MainActivity

/** Re-enables the Home accessibility service after boot or an app update, and launches Nuvio on boot. */
class LauncherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AccessibilityWatchdog.ensureEnabled(context)
                if (LauncherSetup.accessibilityFallbackAllowed(context) || LauncherSetup.isDefaultHome(context)) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    runCatching { context.startActivity(launchIntent) }
                }
            }
        }
    }
}
