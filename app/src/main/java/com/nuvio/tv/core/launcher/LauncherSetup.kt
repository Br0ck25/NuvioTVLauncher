package com.nuvio.tv.core.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Prefer the system HOME role; accessibility is only a fallback for locked-down TV firmware. */
internal object LauncherSetup {
    // Check the actual HOME resolver rather than trusting the role dialog's result: some TV
    // firmware can report a successful request without changing where HOME is dispatched.
    fun isDefaultHome(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /** The system's HOME role is held by this app (what `cmd role get-role-holders` reports). */
    fun isHomeRoleHeld(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    }.getOrDefault(false)

    /**
     * "Home app" as the user sees it: this app holds the Home role, or the resolver already points at
     * it. Google TV can keep opening its own launcher even while we hold the role, so this alone does
     * not mean the Home button works; see [homeWorks].
     */
    fun homeAppSet(context: Context): Boolean = isHomeRoleHeld(context) || isDefaultHome(context)

    /** Home opens this launcher: dispatched to us natively, or taken over by the running accessibility override. */
    fun homeWorks(context: Context): Boolean = isDefaultHome(context) ||
        (LauncherAccessibilityService.isServiceEnabled(context) && LauncherAccessibilityService.isOperational.value)

    /**
     * Android 13+ "restricted settings": for an app that was not installed from a store, the system
     * refuses to enable its accessibility service. The switch can look normal, show the confirm
     * dialog, and then silently stay off, and Android TV has no "Allow restricted settings" menu.
     * True = blocked, false = allowed, null = can't tell. The phone/ADB setup grants it for the user.
     */
    fun restrictedSettingsBlocked(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return@runCatching null
            val mode = ops.unsafeCheckOpNoThrow(
                "android:access_restricted_settings", android.os.Process.myUid(), context.packageName
            )
            // On a Chromecast with Google TV (Android 14) `appops get` said "deny" while this check did not
            // report it as blocked, so the reading is logged to tell the two apart. See overrideAttemptFailed.
            android.util.Log.i("LauncherSetup", "restricted-settings op mode=$mode")
            mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED
        }.getOrNull()
    }

    // The app-op reading above is not reliable on every firmware, so also go by what happened: the user was
    // sent to Android's accessibility screen to turn the override on, came back, and it is still off.
    private const val OVERRIDE_ATTEMPT_AT = "override_attempt_at"

    fun markOverrideAttempt(context: Context) {
        preferences(context).edit().putLong(OVERRIDE_ATTEMPT_AT, System.currentTimeMillis()).apply()
    }

    fun overrideAttemptFailed(context: Context): Boolean {
        val prefs = preferences(context)
        val at = prefs.getLong(OVERRIDE_ATTEMPT_AT, 0L)
        if (at == 0L) return false
        if (LauncherAccessibilityService.isServiceEnabled(context)) {
            prefs.edit().putLong(OVERRIDE_ATTEMPT_AT, 0L).apply()
            return false
        }
        return System.currentTimeMillis() - at > 5_000L
    }

    // An update can silently switch the Home button override off (Android re-applies the restricted-settings
    // block to the new install). Remember that it was on, so after an update we can tell the user and
    // not leave Home quietly landing on the TV's own launcher.
    private const val OVERRIDE_WAS_ON = "override_was_on"
    private const val OVERRIDE_LOST_NOTICE = "override_lost_notice"

    fun noteOverrideWasOn(context: Context) {
        preferences(context).edit().putBoolean(OVERRIDE_WAS_ON, true).apply()
    }

    /**
     * Call at app start and after an update. If the override was on before and is off now, raise a notice
     * (once) and reopen the setup so the user sees it.
     */
    fun checkOverrideLost(context: Context) {
        val prefs = preferences(context)
        // Only after an update (a new version code, or the update broadcast), never because the user
        // switched the override off on purpose.
        val current = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        val last = prefs.getLong(LAST_SEEN_VERSION, current)
        prefs.edit().putLong(LAST_SEEN_VERSION, current).apply()
        val pendingAt = prefs.getLong(UPDATE_PENDING_AT, 0L)
        val updatedJustNow = pendingAt != 0L && System.currentTimeMillis() - pendingAt < 10 * 60 * 1000L
        if (last == current && !updatedJustNow) return
        if (!prefs.getBoolean(OVERRIDE_WAS_ON, false)) return
        if (LauncherAccessibilityService.isServiceEnabled(context)) return // not switched off (yet): checked again later
        prefs.edit().putBoolean(OVERRIDE_WAS_ON, false).putBoolean(OVERRIDE_LOST_NOTICE, true)
            .putLong(UPDATE_PENDING_AT, 0L).apply()
        com.nuvio.tv.ui.screens.setup.LauncherSetupWizardPrefs.reopen(context)
    }

    /** Called from the update broadcast: for the next ten minutes [checkOverrideLost] treats the app as just updated. */
    fun markUpdatePendingCheck(context: Context) {
        preferences(context).edit().putLong(UPDATE_PENDING_AT, System.currentTimeMillis()).apply()
    }

    private const val LAST_SEEN_VERSION = "last_seen_version_code"
    private const val UPDATE_PENDING_AT = "update_pending_override_at"

    fun overrideLostNotice(context: Context): Boolean = preferences(context).getBoolean(OVERRIDE_LOST_NOTICE, false)

    fun clearOverrideLostNotice(context: Context) {
        preferences(context).edit().putBoolean(OVERRIDE_LOST_NOTICE, false).apply()
    }

    // After a trip to a system settings screen (accessibility, notification access) the user has to find
    // their way back. We note that we sent them, and bring the launcher to the front as soon as the
    // thing they went to turn on is on.
    private const val AWAITING_SETTINGS_AT = "awaiting_settings_at"

    fun markAwaitingSettings(context: Context) {
        preferences(context).edit().putLong(AWAITING_SETTINGS_AT, System.currentTimeMillis()).apply()
    }

    /** True once (and clears the note) if the user was recently sent to a settings screen. */
    fun consumeAwaitingSettings(context: Context): Boolean {
        val prefs = preferences(context)
        val at = prefs.getLong(AWAITING_SETTINGS_AT, 0L)
        if (at == 0L) return false
        prefs.edit().remove(AWAITING_SETTINGS_AT).apply()
        return System.currentTimeMillis() - at < 10 * 60 * 1000L
    }

    /** Brings the launcher's window back in front of whatever (Settings, the stock launcher) is showing. */
    fun bringToFront(context: Context) {
        val app = context.applicationContext
        fun front() {
            runCatching {
                app.startActivity(
                    Intent(app, com.nuvio.tv.MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            }
        }
        front()
        // Google TV starts its own home screen a moment after the Home role changes, which lands on top
        // of us (seen on Chromecast with Google TV). Look again twice and take the screen back if needed.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        longArrayOf(800L, 2_000L).forEach { delay ->
            handler.postDelayed({ if (!HomeForegroundDelivery.isResumed) front() }, delay)
        }
    }

    /** Stock launchers that are installed but turned off. Nuvio's takeover needs the stock launcher enabled. */
    fun disabledStockLaunchers(context: Context): List<String> {
        val pm = context.packageManager
        return DeviceCommands.STOCK_LAUNCHERS.filter { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
                !info.enabled || pm.getApplicationEnabledSetting(pkg).let {
                    it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
            }.getOrDefault(false)
        }
    }

    fun handlesHome(context: Context): Boolean {
        val nativeHome = isDefaultHome(context)
        observeNativeHome(context, nativeHome)
        return nativeHome || (accessibilityFallbackAllowed(context) &&
            LauncherAccessibilityService.isServiceEnabled(context) && LauncherAccessibilityService.isOperational.value)
    }

    private const val NATIVE_HOME_OBSERVED = "native_home_observed"

    internal fun observeNativeHome(context: Context, isDefault: Boolean) {
        if (isDefault && !preferences(context).getBoolean(NATIVE_HOME_OBSERVED, false)) {
            preferences(context).edit().putBoolean(NATIVE_HOME_OBSERVED, true).apply()
        }
    }

    // After native HOME has been selected, respect a subsequent choice of a different default launcher.
    // Explicitly selecting the existing Home override action opts back into the authorized fallback.
    internal fun accessibilityFallbackAllowed(context: Context): Boolean =
        !preferences(context).getBoolean(NATIVE_HOME_OBSERVED, false)

    internal fun allowAccessibilityFallback(context: Context) {
        preferences(context).edit().putBoolean(NATIVE_HOME_OBSERVED, false).apply()
    }

    internal const val AUTO_REQUEST_ATTEMPTED = "auto_home_request_attempted"
    internal const val REQUEST_FAILED = "native_home_request_failed"

    internal fun preferences(context: Context) =
        context.getSharedPreferences("launcher_capabilities", Context.MODE_PRIVATE)

    /** At most one automatic native dialog per installation. Manual retries remain available. */
    fun takeAutomaticRoleRequest(context: Context): Intent? {
        val capabilities = LauncherCapabilities.detect(context)
        val prefs = preferences(context)
        if (capabilities.isDefaultHome || capabilities.accessibilityEnabled || capabilities.homeRoleHeld) {
            // Don't surprise an established user later if they deliberately choose another launcher.
            prefs.edit().putBoolean(AUTO_REQUEST_ATTEMPTED, true).apply()
            return null
        }
        if (!HomeCapabilityPolicy.shouldRequestNativeHome(
                attempted = prefs.getBoolean(AUTO_REQUEST_ATTEMPTED, false),
                defaultHome = capabilities.isDefaultHome,
                roleAvailable = capabilities.homeRoleAvailable,
                roleHeld = capabilities.homeRoleHeld,
                accessibilityEnabled = capabilities.accessibilityEnabled
            )) return null
        // Persist before creating/launching, including failures and process death in the dialog.
        if (!prefs.edit().putBoolean(AUTO_REQUEST_ATTEMPTED, true).commit()) return null
        return createHomeRoleRequest(context).also {
            if (it == null) recordRoleRequestResult(context, launchFailed = true)
        }
    }

    private const val ROLE_OFFER_PENDING = "home_role_offer_pending"
    private const val ROLE_OFFER_COUNT = "home_role_offer_count"
    private const val MAX_ROLE_OFFERS = 2

    /**
     * Called after an app update (no UI available): remember to offer the native HOME role the next
     * time Nuvio is in front. Capped so a user who declines is not nagged on every update.
     */
    fun markRoleOfferAfterUpdate(context: Context) {
        val prefs = preferences(context)
        if (prefs.getInt(ROLE_OFFER_COUNT, 0) >= MAX_ROLE_OFFERS) return
        if (!accessibilityFallbackAllowed(context) || isDefaultHome(context)) return
        prefs.edit().putBoolean(ROLE_OFFER_PENDING, true).apply()
    }

    /** Returns the role request to launch (once per pending offer), or null. */
    fun takePendingRoleOffer(context: Context): Intent? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(ROLE_OFFER_PENDING, false)) return null
        val count = prefs.getInt(ROLE_OFFER_COUNT, 0)
        if (!prefs.edit().putBoolean(ROLE_OFFER_PENDING, false).putInt(ROLE_OFFER_COUNT, count + 1).commit()) {
            return null
        }
        if (isDefaultHome(context)) return null
        return createHomeRoleRequest(context)
    }

    fun recordRoleRequestResult(context: Context, launchFailed: Boolean = false) {
        // A denial/no change is not proof of a firmware restriction. Only record thrown failures.
        preferences(context).edit().putBoolean(REQUEST_FAILED, launchFailed).apply()
    }

    /** The caller must launch this for a result, without NEW_TASK, so denial is observable. */
    fun createHomeRoleRequest(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.let(::createHomeRoleRequest)
        }.getOrNull()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    internal fun createHomeRoleRequest(roles: RoleManager): Intent? {
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            return null
        }
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    /** Try directly: package-visibility filtering is not a reason to skip system settings. */
    fun openHomeSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
