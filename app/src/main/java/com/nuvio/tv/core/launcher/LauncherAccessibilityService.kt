package com.nuvio.tv.core.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.nuvio.tv.MainActivity

class LauncherAccessibilityService : AccessibilityService() {

    private var lastHomeHandledTime = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastHomeHandledTime > 300) {
                    lastHomeHandledTime = now
                    launchHome()
                }
            }
            // Consume the HOME key event so the stock launcher doesn't get it
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Fallback for devices where KeyEvent.KEYCODE_HOME isn't directly filtered:
        // if stock Android TV/Google TV launcher comes to foreground, bring Nuvio back
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (isStockLauncherPackage(pkg)) {
                val now = SystemClock.uptimeMillis()
                if (now - lastHomeHandledTime > 500) {
                    lastHomeHandledTime = now
                    launchHome()
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun launchHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
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
        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${LauncherAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val component = colonSplitter.next()
                if (component.equals(expectedComponentName, ignoreCase = true)) {
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
