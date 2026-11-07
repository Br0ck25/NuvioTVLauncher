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
    val hasBanner: Boolean,
    val customLabel: String? = null,
    val customIcon: ImageBitmap? = null,
    val bannerBitmap: ImageBitmap? = null,
    val squareBitmap: ImageBitmap? = null
) {
    val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: label

    val displayIcon: ImageBitmap?
        get() = customIcon ?: icon

    val isBanner: Boolean
        get() {
            val currentIcon = displayIcon ?: return false
            val aspect = currentIcon.width.toFloat() / currentIcon.height.coerceAtLeast(1).toFloat()
            return aspect > BANNER_ASPECT_THRESHOLD || (customIcon == null && hasBanner)
        }

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .setComponent(componentName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** TV banners are ~16:9; anything squarer than this is just the app icon. */
private const val BANNER_ASPECT_THRESHOLD = 1.3f

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
            val component = ComponentName(activity.packageName, activity.name)
            val banner = runCatching { pm.getActivityBanner(component) }.getOrNull()
                ?: runCatching { pm.getApplicationBanner(activity.packageName) }.getOrNull()
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            val bannerIsWide = banner != null &&
                banner.intrinsicWidth > banner.intrinsicHeight * BANNER_ASPECT_THRESHOLD

            fun scaleToBitmap(drawable: android.graphics.drawable.Drawable?, maxW: Float, maxH: Float): ImageBitmap? {
                return runCatching {
                    drawable?.let {
                        val intrinsicWidth = it.intrinsicWidth.coerceAtLeast(1)
                        val intrinsicHeight = it.intrinsicHeight.coerceAtLeast(1)
                        val scale = minOf(maxW / intrinsicWidth, maxH / intrinsicHeight, 1f)
                        it.toBitmap(
                            width = (intrinsicWidth * scale).toInt().coerceAtLeast(1),
                            height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
                        ).asImageBitmap()
                    }
                }.getOrNull()
            }

            val bannerBmp = if (bannerIsWide) scaleToBitmap(banner, 320f, 180f) else null
            val squareBmp = scaleToBitmap(icon ?: banner, 160f, 160f)
            val defaultBmp = if (bannerIsWide) bannerBmp else (squareBmp ?: bannerBmp)

            appsByPackage[activity.packageName] = InstalledApp(
                packageName = activity.packageName,
                label = info.loadLabel(pm).toString().ifBlank { activity.packageName },
                componentName = component,
                icon = defaultBmp,
                hasBanner = bannerIsWide,
                bannerBitmap = bannerBmp,
                squareBitmap = squareBmp
            )
        }
    }
    appsByPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayLabel })
}

/** Never let an uninstalled, disabled, or exported-activity failure crash the launcher. */
fun launchInstalledApp(context: Context, app: InstalledApp): Boolean = runCatching {
    context.startActivity(app.launchIntent())
}.isSuccess
