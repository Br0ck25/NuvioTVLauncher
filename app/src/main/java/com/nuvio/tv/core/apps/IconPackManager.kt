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

@Singleton
class IconPackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IconPackManager"
        private val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
            "com.teslacoilsw.launcher.THEME",
            "com.anddoes.launcher.THEME",
            "com.dlto.atom.launcher.THEME"
        )
    }

    private val appFilterCache = ConcurrentHashMap<String, Map<String, String>>()

    suspend fun getInstalledIconPacks(): List<IconPackInfo> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = mutableListOf<IconPackInfo>()

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
        result.sortedBy { it.name.lowercase() }
    }

    suspend fun loadIconForApp(
        packageName: String,
        componentName: ComponentName?,
        iconPackPackage: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        val appFilter = getOrLoadAppFilter(iconPackPackage)
        val componentKey = componentName?.let { "${it.packageName}/${it.className}" }
        val drawableName = (componentKey?.let { appFilter[it] })
            ?: appFilter[packageName]
            ?: appFilter[packageName.substringAfterLast('.')]
            ?: return@withContext null

        loadDrawableFromPack(iconPackPackage, drawableName)
    }

    suspend fun loadSpecificIcon(
        iconPackPackage: String,
        drawableName: String
    ): ImageBitmap? = withContext(Dispatchers.Default) {
        loadDrawableFromPack(iconPackPackage, drawableName)
    }

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

    private fun getOrLoadAppFilter(iconPackPackage: String): Map<String, String> {
        return appFilterCache.getOrPut(iconPackPackage) {
            parseAppFilter(iconPackPackage)
        }
    }

    private fun parseAppFilter(iconPackPackage: String): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val pm = context.packageManager
        try {
            val packContext = context.createPackageContext(iconPackPackage, Context.CONTEXT_IGNORE_SECURITY)
            val assetManager = packContext.assets
            val parser: XmlPullParser? = try {
                assetManager.open("appfilter.xml").let { stream ->
                    Xml.newPullParser().apply {
                        setInput(stream, "UTF-8")
                    }
                }
            } catch (e: Exception) {
                val res = pm.getResourcesForApplication(iconPackPackage)
                val resId = res.getIdentifier("appfilter", "xml", iconPackPackage)
                if (resId != 0) res.getXml(resId) else null
            }

            if (parser != null) {
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            val cleanComp = component.removePrefix("ComponentInfo{").removeSuffix("}")
                            mapping[cleanComp] = drawable
                            val pkg = cleanComp.substringBefore("/")
                            if (!mapping.containsKey(pkg)) {
                                mapping[pkg] = drawable
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse appfilter.xml for $iconPackPackage", e)
        }
        return mapping
    }

    private fun loadDrawableFromPack(iconPackPackage: String, drawableName: String): ImageBitmap? {
        return runCatching {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(iconPackPackage)
            val resId = res.getIdentifier(drawableName, "drawable", iconPackPackage)
            if (resId == 0) return null
            val drawable = res.getDrawable(resId, null) ?: return null
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
            val maxDim = 160f
            val scale = minOf(maxDim / intrinsicW, maxDim / intrinsicH, 1f)
            val w = (intrinsicW * scale).toInt().coerceAtLeast(1)
            val h = (intrinsicH * scale).toInt().coerceAtLeast(1)
            drawable.toBitmap(width = w, height = h).asImageBitmap()
        }.getOrNull()
    }
}
