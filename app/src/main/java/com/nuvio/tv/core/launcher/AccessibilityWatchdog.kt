package com.nuvio.tv.core.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * How long to wait before writing the accessibility settings again, after [failedWrites] writes in a row that
 * did not stick. The first write is immediate; Android can refuse to enable the service (for example the
 * restricted-settings block it puts back on an app after an update), and then every write was undone at once,
 * which the watchdog's own observer took as a reason to write again: ~1,000 writes a minute on a Chromecast.
 */
internal fun accessibilityWriteBackoffMs(failedWrites: Int): Long = when {
    failedWrites <= 0 -> 0L
    failedWrites == 1 -> 2_000L
    failedWrites == 2 -> 10_000L
    failedWrites == 3 -> 60_000L
    else -> 300_000L
}

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

    // Writes that did not stick since the service was last seen enabled, and when the last one happened.
    @Volatile private var failedWrites = 0
    @Volatile private var lastWriteAtMs = 0L
    private val retryLock = Any()
    private var pendingRetry: Runnable? = null
    private val retryHandler by lazy { Handler(Looper.getMainLooper()) }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    fun isOptedIn(context: Context): Boolean =
        LauncherSetup.preferences(context).getBoolean(OPT_IN, false)

    fun setOptIn(context: Context, optedIn: Boolean) {
        LauncherSetup.preferences(context).edit().putBoolean(OPT_IN, optedIn).apply()
        if (optedIn) ensureEnabled(context)
    }

    /**
     * Adds this service to the enabled list without disturbing other accessibility services. If Android keeps
     * undoing it, the next writes are spaced out ([accessibilityWriteBackoffMs]) instead of repeating at once.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (!isOptedIn(context) || !canWriteSecureSettings(context)) return false
        val app = context.applicationContext
        return runCatching {
            val resolver = app.contentResolver
            val target = ComponentName(app, LauncherAccessibilityService::class.java)
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val listed = LauncherAccessibilityService.containsEnabledComponent(current, target)
            val masterOn = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (listed && masterOn) {
                failedWrites = 0
                return@runCatching true
            }
            // Not enabled: write now, unless a recent write of ours did not stick (then wait, and look again).
            val now = SystemClock.elapsedRealtime()
            val waitMs = accessibilityWriteBackoffMs(failedWrites) - (now - lastWriteAtMs)
            if (lastWriteAtMs != 0L && waitMs > 0L) {
                scheduleRetry(app, waitMs)
                return@runCatching false
            }
            if (!listed) {
                val updated = if (current.isNullOrBlank()) target.flattenToString()
                else "$current:${target.flattenToString()}"
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            }
            if (!masterOn) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            lastWriteAtMs = now
            failedWrites += 1
            true
        }.onFailure { Log.w(TAG, "Unable to enable accessibility service", it) }.getOrDefault(false)
    }

    /** One pending look-again at a time, however many changes arrive in the meantime. */
    private fun scheduleRetry(app: Context, delayMs: Long) {
        synchronized(retryLock) {
            if (pendingRetry != null) return
            val retry = Runnable {
                synchronized(retryLock) { pendingRetry = null }
                ensureEnabled(app)
            }
            pendingRetry = retry
            retryHandler.postDelayed(retry, delayMs.coerceAtLeast(500L))
        }
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
