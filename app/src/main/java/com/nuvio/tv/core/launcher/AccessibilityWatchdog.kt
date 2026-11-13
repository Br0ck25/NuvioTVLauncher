package com.nuvio.tv.core.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps Nuvio's own accessibility service enabled once the user has opted in and the app holds
 * WRITE_SECURE_SETTINGS (granted by [LocalAdbSetup] or a one-time `adb shell pm grant`).
 * Without that permission every entry point is a harmless no-op.
 */
internal object AccessibilityWatchdog {
    private const val TAG = "AccessibilityWatchdog"
    private const val OPT_IN = "accessibility_watchdog_opt_in"
    private const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    @Volatile private var observer: ContentObserver? = null

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    fun isOptedIn(context: Context): Boolean =
        LauncherSetup.preferences(context).getBoolean(OPT_IN, false)

    fun setOptIn(context: Context, optedIn: Boolean) {
        LauncherSetup.preferences(context).edit().putBoolean(OPT_IN, optedIn).apply()
        if (optedIn) ensureEnabled(context)
    }

    /** Adds this service to the enabled list without disturbing other accessibility services. */
    fun ensureEnabled(context: Context): Boolean {
        if (!isOptedIn(context) || !canWriteSecureSettings(context)) return false
        return runCatching {
            val resolver = context.contentResolver
            val target = ComponentName(context, LauncherAccessibilityService::class.java)
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (!LauncherAccessibilityService.containsEnabledComponent(current, target)) {
                val updated = if (current.isNullOrBlank()) target.flattenToString()
                else "$current:${target.flattenToString()}"
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        }.onFailure { Log.w(TAG, "Unable to enable accessibility service", it) }.getOrDefault(false)
    }

    /** Reacts immediately if the system or the user switches the service off while the app lives. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (observer != null) return
        synchronized(this) {
            if (observer != null) return
            val handler = Handler(Looper.getMainLooper())
            val created = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    ensureEnabled(app)
                }
            }
            val registered = runCatching {
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, created
                )
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, created
                )
            }.isSuccess
            if (registered) observer = created
        }
        ensureEnabled(app)
    }
}
