package com.nuvio.tv.core.launcher

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Observations, not a manufacturer-based promise that firmware will dispatch HOME. */
internal data class LauncherCapabilities(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val homeRoleAvailable: Boolean,
    val homeRoleHeld: Boolean,
    val isDefaultHome: Boolean,
    val homeSettingsResolvable: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityOperational: Boolean,
    val selectedHome: ComponentName?,
    val selectedHomeActivities: Set<ComponentName>,
    val nativeRequestFailed: Boolean,
    val accessibilityFallbackAllowed: Boolean = true
) {
    val mechanism: HomeMechanism
        get() = HomeCapabilityPolicy.mechanism(isDefaultHome, accessibilityEnabled, accessibilityOperational, accessibilityFallbackAllowed)

    // An absent API is not proof of an OEM restriction (especially before Android 10).
    val restrictionEvidence: NativeHomeRestriction
        get() = when {
            isDefaultHome -> NativeHomeRestriction.NONE_OBSERVED
            homeRoleHeld -> NativeHomeRestriction.ROLE_RESOLVER_MISMATCH
            nativeRequestFailed -> NativeHomeRestriction.REQUEST_FAILED
            else -> NativeHomeRestriction.UNKNOWN
        }

    companion object {
        fun detect(context: Context): LauncherCapabilities {
            val pm = context.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val selected = runCatching {
                pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let {
                    ComponentName(it.packageName, it.name)
                }
            }.getOrNull()
            val candidates = runCatching {
                pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull {
                    it.activityInfo?.let { info -> ComponentName(info.packageName, info.name) }
                }.toSet()
            }.getOrDefault(emptySet())
            // ResolverActivity is not a selected launcher. Only redirect real declared HOME activities.
            val selectedActivities = candidates.filter { it.packageName == selected?.packageName }.toSet()
            val roles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
            } else null
            val roleAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                runCatching { roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
            val roleHeld = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                runCatching { roles?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
            val enabled = LauncherAccessibilityService.isServiceEnabled(context)
            return LauncherCapabilities(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                sdkVersion = Build.VERSION.SDK_INT,
                homeRoleAvailable = roleAvailable,
                homeRoleHeld = roleHeld,
                isDefaultHome = selected?.packageName == context.packageName,
                homeSettingsResolvable = runCatching {
                    pm.resolveActivity(Intent(Settings.ACTION_HOME_SETTINGS), 0) != null
                }.getOrDefault(false),
                accessibilityEnabled = enabled,
                accessibilityOperational = enabled && LauncherAccessibilityService.isOperational.value,
                selectedHome = selected,
                selectedHomeActivities = selectedActivities,
                nativeRequestFailed = LauncherSetup.preferences(context).getBoolean(LauncherSetup.REQUEST_FAILED, false),
                accessibilityFallbackAllowed = LauncherSetup.accessibilityFallbackAllowed(context)
            )
        }
    }
}
