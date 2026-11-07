package com.nuvio.tv.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class IconPackInfo(
    val packageName: String,
    val name: String,
    val icon: ImageBitmap?
)

data class IconPackEntry(
    val drawableName: String,
    val icon: ImageBitmap
)

/**
 * Manages discovery, parsing, and drawable resolution for standard APK-based Android TV and
 * Projectivy icon packs (structured with assets/appfilter.xml and res/ drawable resources).
 */
@Singleton
class IconPackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IconPackManager"

        /** Standard launcher and Android TV / Projectivy icon pack intent filter actions. */
        val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.dlto.atom.launcher.THEME",
            "com.actionlauncher.THEME",
            "mobi.bbase.ahome.THEME",
            "com.sonymobile.home.ICON_PACK",
            "com.spocky.projengmenu.ACTION_THEME",
            "com.spocky.projengmenu.THEME",
            "com.spocky.projectivy.THEME",
            "com.spocky.projectivy.ICON_PACK",
            "com.projectivy.intent.action.ICON_PACK",
            "com.projectivy.theme.ICON_PACK",
            "org.adw.launcher.icons.ACTION_PICK_ICON"
        )
    }

    private val appFilterCache = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Scans installed packages to detect valid icon packs matching known launcher/theme intents
     * or containing `assets/appfilter.xml` / `assets/appfilter_tv.xml`.
     */
    suspend fun getInstalledIconPacks(): List<IconPackInfo> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = mutableListOf<IconPackInfo>()

        // 1. Query all packages advertising launcher theme/icon actions
        ICON_PACK_ACTIONS.forEach { action ->
            val intent = Intent(action)
            val activities = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())

            activities.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName || !seen.add(pkg)) return@forEach
                val label = runCatching {
                    resolveInfo.loadLabel(pm).toString()
                }.getOrDefault(pkg)
                val icon = runCatching {
                    resolveInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                }.getOrNull()
                result.add(
                    IconPackInfo(
                        packageName = pkg,
                        name = label,
                        icon = icon
                    )
                )
            }
        }

        // 2. Query leanback and standard launcher activities for TV icon pack APKs
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ).forEach { intent ->
            val activities = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrDefault(emptyList())

            activities.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName || seen.contains(pkg)) return@forEach
                if (hasAppFilter(pkg)) {
                    seen.add(pkg)
                    val label = runCatching {
                        resolveInfo.loadLabel(pm).toString()
                    }.getOrDefault(pkg)
                    val icon = runCatching {
                        resolveInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                    }.getOrNull()
                    result.add(
                        IconPackInfo(
                            packageName = pkg,
                            name = label,
                            icon = icon
                        )
                    )
                }
            }
        }

        // 3. Fallback scan: query installed applications that contain appfilter assets
        val installedApps = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        installedApps.forEach { appInfo ->
            val pkg = appInfo.packageName
            if (pkg == context.packageName || seen.contains(pkg)) return@forEach
            if (hasAppFilter(pkg)) {
                seen.add(pkg)
                val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching {
                    appInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
                }.getOrNull()
                result.add(
                    IconPackInfo(
                        packageName = pkg,
                        name = label,
                        icon = icon
                    )
                )
            }
        }

        result.sortedBy { it.name.lowercase() }
    }

    /**
     * Checks whether an installed package has an `appfilter.xml` or `appfilter_tv.xml` asset or resource.
     */
    fun hasAppFilter(packageName: String): Boolean {
        return runCatching {
            val packContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            val hasAsset = runCatching {
                packContext.assets.open("appfilter.xml").use { true }
            }.getOrDefault(false) || runCatching {
                packContext.assets.open("appfilter_tv.xml").use { true }
            }.getOrDefault(false) || runCatching {
                packContext.assets.open("themeinfo.xml").use { true }
            }.getOrDefault(false)

            if (hasAsset) return true

            val res = context.packageManager.getResourcesForApplication(packageName)
            val resId = res.getIdentifier("appfilter", "xml", packageName)
            val resTvId = res.getIdentifier("appfilter_tv", "xml", packageName)
            resId != 0 || resTvId != 0
        }.getOrDefault(false)
    }

    /**
     * Loads the icon or TV banner for a given app from the selected icon pack APK.
     * Looks up by exact ComponentInfo key first, followed by package name fallbacks.
     */
    suspend fun loadIconForApp(
        packageName: String,
        componentName: ComponentName?,
        iconPackPackage: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        val appFilter = getOrLoadAppFilter(iconPackPackage)
        if (appFilter.isEmpty()) return@withContext null

        val exactKey = componentName?.let { "${it.packageName}/${it.className}" }
        val shortKey = componentName?.let { "${it.packageName}/${it.shortClassName}" }

        val drawableName = (exactKey?.let { appFilter[it] ?: appFilter[it.lowercase()] })
            ?: (shortKey?.let { appFilter[it] ?: appFilter[it.lowercase()] })
            ?: appFilter[packageName]
            ?: appFilter[packageName.lowercase()]
            ?: appFilter[packageName.substringAfterLast('.')]
            ?: appFilter[packageName.substringAfterLast('.').lowercase()]
            ?: return@withContext null

        loadDrawableFromPack(iconPackPackage, drawableName)
    }

    /**
     * Loads a specific named drawable from an icon pack APK.
     */
    suspend fun loadSpecificIcon(
        iconPackPackage: String,
        drawableName: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        loadDrawableFromPack(iconPackPackage, drawableName)
    }

    /**
     * Loads available icons from an icon pack for picker UI lists.
     */
    suspend fun loadIconsForPack(
        iconPackPackage: String,
        limit: Int = 120
    ): List<IconPackEntry> = withContext(Dispatchers.Default) {
        val appFilter = getOrLoadAppFilter(iconPackPackage)
        val drawableNames = appFilter.values.distinct().take(limit)
        drawableNames.mapNotNull { name ->
            val icon = loadDrawableFromPack(iconPackPackage, name) ?: return@mapNotNull null
            IconPackEntry(drawableName = name, icon = icon)
        }
    }

    /**
     * Clears cached appfilter maps (e.g. when an icon pack is updated or uninstalled).
     */
    fun invalidateCache(packageName: String? = null) {
        if (packageName == null) {
            appFilterCache.clear()
        } else {
            appFilterCache.remove(packageName)
        }
    }

    /**
     * Verifies if a given icon pack is currently installed.
     */
    fun isIconPackInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun getOrLoadAppFilter(iconPackPackage: String): Map<String, String> {
        return appFilterCache.getOrPut(iconPackPackage) {
            parseAppFilter(iconPackPackage)
        }
    }

    /**
     * Parses `appfilter.xml` from the external package's assets or resources using [XmlPullParser].
     */
    fun parseAppFilter(iconPackPackage: String): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val pm = context.packageManager
        try {
            val packContext = runCatching {
                context.createPackageContext(iconPackPackage, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()

            var parser: XmlPullParser? = null
            var inputStream: InputStream? = null

            // 1. Try opening assets/appfilter.xml or assets/appfilter_tv.xml
            if (packContext != null) {
                for (assetName in listOf("appfilter.xml", "appfilter_tv.xml", "drawable.xml")) {
                    try {
                        inputStream = packContext.assets.open(assetName)
                        val factory = XmlPullParserFactory.newInstance().apply {
                            isNamespaceAware = false
                        }
                        parser = factory.newPullParser().apply {
                            setInput(inputStream, "UTF-8")
                        }
                        break
                    } catch (_: Exception) {
                        // Continue to next asset candidate
                    }
                }
            }

            // 2. Try res/xml/appfilter
            if (parser == null) {
                val res = pm.getResourcesForApplication(iconPackPackage)
                val resId = res.getIdentifier("appfilter", "xml", iconPackPackage).takeIf { it != 0 }
                    ?: res.getIdentifier("appfilter_tv", "xml", iconPackPackage).takeIf { it != 0 }
                if (resId != null && resId != 0) {
                    parser = res.getXml(resId)
                }
            }

            if (parser != null) {
                try {
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                            val component = parser.getAttributeValue(null, "component")
                            val pkgAttr = parser.getAttributeValue(null, "package")
                            val drawable = parser.getAttributeValue(null, "drawable")

                            if (!drawable.isNullOrBlank()) {
                                if (!component.isNullOrBlank()) {
                                    val cleanComp = component.removePrefix("ComponentInfo{").removeSuffix("}").trim()
                                    mapping[cleanComp] = drawable
                                    mapping[cleanComp.lowercase()] = drawable

                                    if (cleanComp.contains("/")) {
                                        val pkg = cleanComp.substringBefore("/")
                                        val cls = cleanComp.substringAfter("/")

                                        if (cls.startsWith(".")) {
                                            mapping["$pkg$cls"] = drawable
                                            mapping["$pkg/$pkg$cls"] = drawable
                                        }

                                        // Fallback package mapping: TV / Leanback activities take precedence
                                        val isTvActivity = cls.contains("tv", ignoreCase = true) ||
                                            cls.contains("leanback", ignoreCase = true)
                                        if (!mapping.containsKey(pkg) || isTvActivity) {
                                            mapping[pkg] = drawable
                                            mapping[pkg.lowercase()] = drawable
                                        }
                                    } else {
                                        mapping[cleanComp] = drawable
                                        mapping[cleanComp.lowercase()] = drawable
                                    }
                                }

                                if (!pkgAttr.isNullOrBlank()) {
                                    if (!mapping.containsKey(pkgAttr)) {
                                        mapping[pkgAttr] = drawable
                                        mapping[pkgAttr.lowercase()] = drawable
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                } finally {
                    inputStream?.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse appfilter.xml for $iconPackPackage", e)
        }
        return mapping
    }

    /**
     * Resolves the named drawable from the external icon pack APK using [PackageManager.getResourcesForApplication].
     * Supports both "drawable" and "mipmap" resource types and scales 16:9 banners and icons crisply without distortion.
     */
    fun loadDrawableFromPack(iconPackPackage: String, drawableName: String): ImageBitmap? {
        return runCatching {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(iconPackPackage)

            // 1. Resolve resource identifier via "drawable", fallback to "mipmap"
            var resId = res.getIdentifier(drawableName, "drawable", iconPackPackage)
            if (resId == 0) {
                resId = res.getIdentifier(drawableName, "mipmap", iconPackPackage)
            }
            if (resId == 0) {
                val clean = drawableName.lowercase().replace('-', '_')
                resId = res.getIdentifier(clean, "drawable", iconPackPackage)
                if (resId == 0) {
                    resId = res.getIdentifier(clean, "mipmap", iconPackPackage)
                }
            }
            if (resId == 0) return null

            val drawable: Drawable = res.getDrawable(resId, null) ?: return null
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)

            // TV banners (~16:9) are sized up to 640x360 (2x 320x180); square icons up to 192x192
            val isBanner = (intrinsicW.toFloat() / intrinsicH.toFloat()) > 1.3f
            val (maxW, maxH) = if (isBanner) 640f to 360f else 192f to 192f

            val scale = minOf(maxW / intrinsicW, maxH / intrinsicH, 1f)
            val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
            val h = (intrinsicH * scale).toInt().coerceAtLeast(1)

            drawable.toBitmap(width = w, height = h).asImageBitmap()
        }.getOrNull()
    }
}
