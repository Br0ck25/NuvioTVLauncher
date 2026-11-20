package com.nuvio.tv.core.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nuvio.tv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
                // Why does the resolver disagree with Settings' Home picker? Log what it actually returns,
                // now and shortly after (the preferred-Home binding can settle a moment after an update).
                logHomeResolution(context.applicationContext, "now")
                Handler(Looper.getMainLooper()).postDelayed(
                    { logHomeResolution(context.applicationContext, "+10s") }, 10_000L
                )
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    LauncherSetup.markRoleOfferAfterUpdate(context)
                    LauncherSetup.markUpdatePendingCheck(context)
                    // Android can switch the override off while it installs the update, a moment after this
                    // broadcast. Look again once it has settled and remember it for the next time the app opens.
                    val appContext = context.applicationContext
                    Handler(Looper.getMainLooper()).postDelayed({
                        restoreHomeAfterUpdate(appContext)
                        healOrNotifyAfterUpdate(appContext)
                    }, 8_000L)
                    // An update undoes the ahead-of-time compile and can reset the background settings: apply both again.
                    Handler(Looper.getMainLooper()).postDelayed({ reapplyBackgroundSetup(appContext, compile = true) }, 25_000L)
                } else {
                    // After a restart Android can put the standby setting back; the compile survives a restart.
                    val appContext = context.applicationContext
                    Handler(Looper.getMainLooper()).postDelayed({ reapplyBackgroundSetup(appContext, compile = false) }, 60_000L)
                }
                // The service takes over by itself when it is bound; this is only the backup.
                val serviceBound = LauncherAccessibilityService.isOperational.value
                if (!serviceBound && (fallbackAllowed || defaultHome)) {
                    val appContext = context.applicationContext
                    relaunch(appContext, attempt = 0)
                    // Other launchers (e.g. the stock one) can start their own screens right after an
                    // update. If Nuvio still isn't in front, try again a couple of times.
                    val handler = Handler(Looper.getMainLooper())
                    RETRY_DELAYS_MS.forEachIndexed { index, delay ->
                        handler.postDelayed({
                            if (HomeForegroundDelivery.isResumed) {
                                Log.i(TAG, "Retry ${index + 1} skipped: Nuvio already in foreground")
                            } else {
                                relaunch(appContext, attempt = index + 1)
                            }
                        }, delay)
                    }
                } else {
                    Log.i(TAG, "Skipping home relaunch: not default home and accessibility fallback disallowed")
                }
            }
        }
    }

    /**
     * After an update Android can put the restricted-settings block back on this app, so the Home button
     * override stops working (seen on Chromecast with Google TV: the update left it off and the TV on its own
     * launcher). If the user set this up with the phone setup before, this TV is already paired with the app, so
     * run the same step again over Wireless debugging; otherwise, or if that fails, tell the user next time the
     * app opens.
     */
    private fun healOrNotifyAfterUpdate(context: Context) {
        if (!LauncherSetup.isOverrideLostAfterUpdate(context, requireOperational = true)) return
        val setup = runCatching { LocalAdbSetupFactory.create(context) }.getOrNull()
        if (setup == null || !AccessibilityWatchdog.isOptedIn(context)) {
            LauncherSetup.checkOverrideLost(context, requireOperational = true)
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            val result = withTimeoutOrNull(40_000L) { setup.run(DeviceAction.ENABLE_OVERRIDE, null) }
            Log.i(TAG, "Post-update override repair: ${result?.fold({ it }, { "failed: ${it.message}" }) ?: "timed out"}")
            delay(6_000L)
            // Still not bound (Wireless debugging off, pairing lost, ...): fall back to telling the user.
            LauncherSetup.checkOverrideLost(context, requireOperational = true)
        }
    }

    /**
     * Updating the app over itself can drop Android's saved "this is the Home app" choice. With the TV's own
     * launcher turned off or removed, Home then falls to Google's recovery screen, which closes at once and
     * reopens until the system crashes (seen on Chromecast with Google TV). If this app was the Home app before and
     * is not now, set it again over Wireless debugging, the same step the phone setup runs.
     */
    private fun restoreHomeAfterUpdate(context: Context) {
        if (!LauncherSetup.wasNativeHome(context) || LauncherSetup.isDefaultHome(context)) return
        val setup = runCatching { LocalAdbSetupFactory.create(context) }.getOrNull() ?: return
        CoroutineScope(Dispatchers.Default).launch {
            val result = withTimeoutOrNull(40_000L) { setup.run(DeviceAction.MAKE_HOME, null) }
            Log.i(TAG, "Post-update Home repair: ${result?.fold({ it }, { "failed: ${it.message}" }) ?: "timed out"}")
        }
    }

    /**
     * Re-applies "keep the app running in the background" and, after an update, the ahead-of-time compile, over Wireless
     * debugging, on a TV that was set up here before (the pairing is saved). Quietly does nothing otherwise: no pairing,
     * Wireless debugging switched off, or a TV that refuses.
     */
    private fun reapplyBackgroundSetup(context: Context, compile: Boolean) {
        if (!AccessibilityWatchdog.isOptedIn(context)) return
        val setup = runCatching { LocalAdbSetupFactory.create(context) }.getOrNull() ?: return
        CoroutineScope(Dispatchers.Default).launch {
            val kept = withTimeoutOrNull(40_000L) { setup.run(DeviceAction.KEEP_RUNNING, null) }
            Log.i(TAG, "Background setup: ${kept?.fold({ it }, { "failed: ${it.message}" }) ?: "timed out"}")
            if (compile) {
                val done = withTimeoutOrNull(300_000L) { setup.run(DeviceAction.OPTIMIZE_SPEED, null) }
                Log.i(TAG, "Compile after update: ${done?.fold({ it }, { "failed: ${it.message}" }) ?: "timed out"}")
            }
        }
    }

    private fun logHomeResolution(context: Context, whenLabel: String) {
        val caps = LauncherCapabilities.detect(context)
        Log.i(
            TAG,
            "Home resolution ($whenLabel): resolver=${caps.selectedHome?.flattenToShortString()} " +
                "roleHeld=${caps.homeRoleHeld} isDefaultHome=${caps.isDefaultHome} " +
                "candidates=${caps.selectedHomeActivities.map { it.flattenToShortString() }}"
        )
    }

    private fun relaunch(context: Context, attempt: Int) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(LauncherHomeRequest.EXTRA_GO_HOME, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launchIntent) }
            .onSuccess { Log.i(TAG, "Home relaunch #$attempt requested (may still be blocked by the system; check ActivityTaskManager)") }
            .onFailure { Log.w(TAG, "Home relaunch #$attempt failed", it) }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(3_000L, 8_000L)
    }
}
