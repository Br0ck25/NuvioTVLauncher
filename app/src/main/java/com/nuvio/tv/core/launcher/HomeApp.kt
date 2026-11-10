package com.nuvio.tv.core.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Standard "be the device's Home app" plumbing, the same way FLauncher, ArcLauncher and
 * Projectivy do it: the manifest declares MAIN + HOME + DEFAULT on [com.nuvio.tv.MainActivity],
 * and the system, not the app, decides when it is the Home app. This only asks for that choice
 * through the framework's own UI. No accessibility service, no ADB, no key interception.
 */
object HomeApp {
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** True when the system currently resolves the Home button to this app. */
    fun isDefault(context: Context): Boolean {
        val resolved = context.packageManager
            .resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return resolved == context.packageName
    }

    /** Android 10+ has a role dialog for Home; older versions use the Home settings page or the chooser. */
    fun roleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME)) return null
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    /** The system's "Home app" picker, when the firmware has one. */
    fun homeSettingsIntent(context: Context): Intent? =
        Intent(Settings.ACTION_HOME_SETTINGS).takeIf { it.resolves(context) }

    fun settingsIntent(context: Context): Intent? =
        Intent(Settings.ACTION_SETTINGS).takeIf { it.resolves(context) }

    /**
     * The best intent to make this app the Home app, in order: the role dialog, the Home settings
     * page, then Android settings. Null only if the device has none of them.
     */
    fun bestRequestIntent(context: Context): Intent? =
        roleRequestIntent(context) ?: homeSettingsIntent(context) ?: settingsIntent(context)

    private fun Intent.resolves(context: Context): Boolean =
        resolveActivity(context.packageManager) != null
}
