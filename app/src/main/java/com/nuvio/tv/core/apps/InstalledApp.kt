package com.nuvio.tv.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launchable package, with a TV banner first and a correctly proportioned icon fallback. */
@Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val componentName: ComponentName,
    val icon: ImageBitmap?,
    val hasBanner: Boolean
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .setComponent(componentName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

internal suspend fun queryInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.Default) {
    val pm = context.packageManager
    val appsByPackage = linkedMapOf<String, InstalledApp>()
    // Leanback entries win when a package exposes both kinds of launcher activity.
    listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        pm.queryIntentActivities(query, PackageManager.MATCH_ALL).forEach { info ->
            val activity = info.activityInfo ?: return@forEach
            if (activity.packageName == context.packageName || appsByPackage.containsKey(activity.packageName)) {
                return@forEach
            }
            val banner = runCatching { info.loadBanner(pm) }.getOrNull()
            val drawable = banner ?: runCatching { info.loadIcon(pm) }.getOrNull()
            val bitmap = runCatching {
                drawable?.let {
                    val intrinsicWidth = it.intrinsicWidth.coerceAtLeast(1)
                    val intrinsicHeight = it.intrinsicHeight.coerceAtLeast(1)
                    val scale = minOf(
                        320f / intrinsicWidth,
                        180f / intrinsicHeight,
                        1f
                    )
                    it.toBitmap(
                        width = (intrinsicWidth * scale).toInt().coerceAtLeast(1),
                        height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
                    ).asImageBitmap()
                }
            }.getOrNull()
            appsByPackage[activity.packageName] = InstalledApp(
                packageName = activity.packageName,
                label = info.loadLabel(pm).toString().ifBlank { activity.packageName },
                componentName = ComponentName(activity.packageName, activity.name),
                icon = bitmap,
                hasBanner = banner != null
            )
        }
    }
    appsByPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/** Never let an uninstalled, disabled, or exported-activity failure crash the launcher. */
fun launchInstalledApp(context: Context, app: InstalledApp): Boolean = runCatching {
    context.startActivity(app.launchIntent())
}.isSuccess
