package com.nuvio.tv.core.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.nuvio.tv.MainActivity

class LauncherAccessibilityService : AccessibilityService() {

    /** When the app was last brought forward for HOME, so the stock launcher cannot double-fire. */
    private var lastHomeLaunchTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return super.onKeyEvent(event)
        // Android normally reserves HOME for system policy, even when our own window is in
        // front. Do not hand it back expecting Activity.dispatchKeyEvent to receive it. Forward
        // every completed press to the one singleTask MainActivity instead.
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            launchHome(event.eventTime)
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (isStockLauncherPackage(pkg)) {
                val now = SystemClock.uptimeMillis()
                if (now - lastHomeLaunchTime > HOME_LAUNCH_DEBOUNCE_MS) {
                    lastHomeLaunchTime = now
                    launchHome(now)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun launchHome(pressedAtMs: Long) {
        lastHomeLaunchTime = SystemClock.uptimeMillis()
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                // Explicit + tagged so MainActivity lands on Home at the top even when the
                // requested screen is a detail page, search, settings, or the player.
                putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
                // Gesture timing must survive the first press bringing Nuvio into the foreground.
                // The Activity, not a second service-local detector, classifies single/double taps.
                putExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS, pressedAtMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch home activity", e)
        }
    }

    private fun isStockLauncherPackage(pkg: String): Boolean {
        return when (pkg) {
            "com.google.android.apps.tv.launcherx", // Google TV launcher
            "com.google.android.tvlauncher",        // Android TV Leanback launcher
            "com.amazon.tv.launcher",               // Fire TV launcher
            "com.android.launcher",
            "com.android.launcher3" -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "LauncherAccessibility"

        /** Suppresses re-launching when the stock launcher window shows up right after a HOME press. */
        private const val HOME_LAUNCH_DEBOUNCE_MS = 400L

        fun isServiceEnabled(context: Context): Boolean {
            val targetComponent = ComponentName(context, LauncherAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentStr = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentStr)
                if (enabledComponent != null && enabledComponent == targetComponent) {
                    return true
                }
                if (componentStr.equals("${context.packageName}/${LauncherAccessibilityService::class.java.name}", ignoreCase = true) ||
                    componentStr.equals("${context.packageName}/${LauncherAccessibilityService::class.java.canonicalName}", ignoreCase = true) ||
                    componentStr.contains(LauncherAccessibilityService::class.java.simpleName, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            runCatching {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
