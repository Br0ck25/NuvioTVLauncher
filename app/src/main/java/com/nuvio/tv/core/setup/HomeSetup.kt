package com.nuvio.tv.core.setup

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * How Nuvio becomes the Home app, and how the app reports whether it is.
 *
 * There is no key interception here: Nuvio only declares `MAIN`/`HOME`/`DEFAULT` in its manifest and
 * then asks Android — through the system Home-app dialog (`ROLE_HOME`) or the system Home settings —
 * to point HOME at it. That is the same contract FLauncher and ArcLauncher rely on. The third way in
 * is [LocalAdbSetup], which sets the Home preference over the TV's own Wireless debugging.
 */
internal object HomeSetup {

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Checks the actual HOME resolver rather than trusting a dialog result: some TV firmware reports
     * a successful request without changing where HOME is dispatched.
     */
    fun isDefaultHome(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /** True when the one-time ADB grant is in place, so the setup can be re-run without a code. */
    fun hasWriteSecureSettings(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** A resolvable settings handler is not proof its UI allows a third-party default, only that it opens. */
    fun homeSettingsAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveActivity(Intent(Settings.ACTION_HOME_SETTINGS), 0) != null
    }.getOrDefault(false)

    fun openHomeSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /**
     * The system's own "use Nuvio as Home?" confirmation, or null when this Android release has no
     * HOME role or Nuvio already holds it. The caller must launch it for a result, without NEW_TASK,
     * so a denial stays observable.
     */
    fun createHomeRoleRequest(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.let(::createHomeRoleRequest)
        }.getOrNull()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun createHomeRoleRequest(roles: RoleManager): Intent? {
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            return null
        }
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }
}
