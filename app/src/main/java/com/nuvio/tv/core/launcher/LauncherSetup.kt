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

    fun handlesHome(context: Context): Boolean =
        isDefaultHome(context) || LauncherAccessibilityService.isServiceEnabled(context)

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
