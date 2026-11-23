package com.nuvio.tv.core.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val CUSTOM_ICON_DIR = "custom_app_icons"
/** Longest side of a picked icon image, in pixels. */
private const val CUSTOM_ICON_MAX_PX = 512

/** One app that can be opened from the launcher. */
data class InstalledApp(
    val packageName: String,
    /** The name shown: the user's own name for the app if they gave one, else the app's. */
    val label: String,
    /** True when the app has a TV (leanback) entry; those show their banner. */
    val isTvApp: Boolean,
    /** The name the app itself uses. */
    val originalLabel: String = label,
    /** The activity the app opens from the launcher; icon packs are matched on it. */
    val componentName: ComponentName? = null,
    /** Changes whenever the icon pack or this app's chosen icon changes, so cards load the new image. */
    val iconKey: String = "",
    /** The user hid this app from the Home row. It still shows (dimmed) on the Apps page so it can be shown again. */
    val hidden: Boolean = false,
    /** The user hid this app from the Apps page (this profile). The gear panel's Apps still lists it. */
    val hiddenOnPage: Boolean = false
)

/**
 * The apps on this device, the way FLauncher / ArcLauncher list them: everything with a
 * LEANBACK_LAUNCHER entry, plus (for phones and apps with only a normal icon) LAUNCHER entries.
 * Updates itself when an app is installed, updated or removed.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconPackManager: IconPackManager,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager
) {
    private val pm: PackageManager get() = context.packageManager

    // Banners are 320x180, icons much smaller; 48 entries covers a home row + the Apps grid.
    private val images = LruCache<String, Bitmap>(48)

    // Everything here (the order, hidden apps, names, icon pack and icon picks) belongs to the Nuvio profile in use: the
    // first profile keeps the original file, each other profile has its own ("launcher_apps_p<id>") and starts with the
    // usual A to Z. The Apps page and the Home row share it within a profile.
    private fun prefsFor(profileId: Int) =
        com.nuvio.tv.core.profile.ActiveProfile.prefsFor(context, "launcher_apps", profileId)

    @Volatile private var prefs = prefsFor(profileManager.activeProfileId.value)

    // The order the user arranged (package names). Apps not in it follow, A to Z.
    private val savedOrder = MutableStateFlow(AppOrder.decode(prefs.getString(KEY_ORDER, null)))
    private val savedLabels = MutableStateFlow(AppLabels.decode(prefs.getString(KEY_LABELS, null)))
    // The icon pack used for every app (a package name), and icons picked for single apps.
    private val savedPack = MutableStateFlow(prefs.getString(KEY_ICON_PACK, null)?.takeIf { it.isNotBlank() })
    private val savedIcons = MutableStateFlow(AppLabels.decode(prefs.getString(KEY_ICONS, null)))
    // Apps the user hid from the Home row (package names).
    private val savedHidden = MutableStateFlow(AppHidden.decode(prefs.getString(KEY_HIDDEN, null)))
    // Apps the user hid from the Apps page (package names).
    private val savedHiddenPage = MutableStateFlow(AppHidden.decode(prefs.getString(KEY_HIDDEN_PAGE, null)))

    init {
        // Switching profile loads that profile's arrangement.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
            com.nuvio.tv.core.profile.ActiveProfile.baselineReady.await()
            profileManager.activeProfileId.collect { id ->
                val next = prefsFor(id)
                // Always read again: a profile that existed before gets its one-time copy only now (see ActiveProfile).
                prefs = next
                images.evictAll()
                savedOrder.value = AppOrder.decode(next.getString(KEY_ORDER, null))
                savedLabels.value = AppLabels.decode(next.getString(KEY_LABELS, null))
                savedPack.value = next.getString(KEY_ICON_PACK, null)?.takeIf { it.isNotBlank() }
                savedIcons.value = AppLabels.decode(next.getString(KEY_ICONS, null))
                savedHidden.value = AppHidden.decode(next.getString(KEY_HIDDEN, null))
                savedHiddenPage.value = AppHidden.decode(next.getString(KEY_HIDDEN_PAGE, null))
            }
        }
    }
    val order: StateFlow<List<String>> = savedOrder.asStateFlow()
    val selectedIconPack: StateFlow<String?> = savedPack.asStateFlow()

    /** Emits the current list at once, then again whenever the set of apps or their order changes. */
    val apps: Flow<List<InstalledApp>> = combine(installed(), savedOrder, savedLabels, savedPack, savedIcons, savedHidden, savedHiddenPage) { values ->
        @Suppress("UNCHECKED_CAST")
        val list = values[0] as List<InstalledApp>
        @Suppress("UNCHECKED_CAST")
        val order = values[1] as List<String>
        @Suppress("UNCHECKED_CAST")
        val labels = values[2] as Map<String, String>
        val pack = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val icons = values[4] as Map<String, String>
        @Suppress("UNCHECKED_CAST")
        val hidden = values[5] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val hiddenPage = values[6] as Set<String>
        AppOrder.apply(
            list.map { app ->
                val named = labels[app.packageName]?.let { app.copy(label = it) } ?: app
                named.copy(iconKey = IconChoice.key(pack, icons[app.packageName]), hidden = app.packageName in hidden, hiddenOnPage = app.packageName in hiddenPage)
            },
            order
        )
    }

    /** The apps the Home row shows: [apps] without the ones the user hid. */
    val homeApps: Flow<List<InstalledApp>> = apps.map { list -> list.filterNot { it.hidden } }

    /** Hides [app] from the Home row ([hidden] true) or shows it there again. Nothing is uninstalled. */
    fun setHidden(app: InstalledApp, hidden: Boolean) {
        val updated = AppHidden.set(savedHidden.value, app.packageName, hidden)
        prefs.edit().putString(KEY_HIDDEN, AppHidden.encode(updated)).apply()
        savedHidden.value = updated
    }

    /** Hides [app] from the Apps page ([hidden] true) or shows it there again, for the active profile. Nothing is uninstalled. */
    fun setHiddenOnPage(app: InstalledApp, hidden: Boolean) {
        val updated = AppHidden.set(savedHiddenPage.value, app.packageName, hidden)
        prefs.edit().putString(KEY_HIDDEN_PAGE, AppHidden.encode(updated)).apply()
        savedHiddenPage.value = updated
    }

    /** Uses the icon pack [packageName] for every app; null goes back to each app's own icon. Per-app picks are kept. */
    fun setIconPack(packageName: String?) {
        val clean = packageName?.takeIf { it.isNotBlank() }
        prefs.edit().apply { if (clean == null) remove(KEY_ICON_PACK) else putString(KEY_ICON_PACK, clean) }.apply()
        images.evictAll()
        savedPack.value = clean
    }

    /**
     * Picks the icon for one app: [choice] is an icon from a pack ([IconChoice.fromPack]), [IconChoice.SYSTEM]
     * (the app's own, whatever the pack says) or null (follow the icon pack again).
     */
    fun setAppIcon(app: InstalledApp, choice: String?) {
        val previous = savedIcons.value[app.packageName]
        val updated = if (choice.isNullOrBlank()) savedIcons.value - app.packageName else savedIcons.value + (app.packageName to choice)
        prefs.edit().putString(KEY_ICONS, AppLabels.encode(updated)).apply()
        images.evictAll()
        savedIcons.value = updated
        // An image the user picked earlier is not needed any more. Only this profile's own files are removed.
        if (previous != null && previous != choice) {
            IconChoice.fileName(previous)
                ?.takeIf { it.startsWith("p${profileManager.activeProfileId.value}_") }
                ?.let { runCatching { File(customIconDir(), it).delete() } }
        }
    }

    private fun customIconDir() = File(context.filesDir, CUSTOM_ICON_DIR)

    /**
     * Uses the image at [uri] (picked in a file manager) as [app]'s icon, for the active profile. The image is copied into
     * the app's own storage and scaled down, so it keeps working if the original is moved or deleted. False when the
     * file is not an image the device can read.
     */
    suspend fun setCustomIcon(app: InstalledApp, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CUSTOM_ICON_MAX_PX) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            val longest = maxOf(decoded.width, decoded.height)
            val bitmap = if (longest > CUSTOM_ICON_MAX_PX) {
                val scale = CUSTOM_ICON_MAX_PX.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true
                )
            } else decoded
            val dir = customIconDir().apply { mkdirs() }
            val name = "p${profileManager.activeProfileId.value}_${app.packageName}_${System.currentTimeMillis()}.png"
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            setAppIcon(app, IconChoice.fromFile(name))
            true
        }.getOrDefault(false)
    }

    /** The icon pack chosen for this app alone, or null when it follows the global pack. */
    fun appIconChoice(app: InstalledApp): String? = savedIcons.value[app.packageName]

    /** Gives [app] the name [name] on the Apps page and the Home row; blank puts the app's own name back. */
    fun rename(app: InstalledApp, name: String?) {
        val updated = AppLabels.set(savedLabels.value, app.packageName, name)
        prefs.edit().putString(KEY_LABELS, AppLabels.encode(updated)).apply()
        savedLabels.value = updated
    }

    /**
     * Moves [app] one place earlier (-1) or later (+1) in the list as it is shown now. With [skipHidden] (the Home
     * row) the app swaps places with the next app that is shown there, not with a hidden one in between.
     */
    fun move(shown: List<InstalledApp>, app: InstalledApp, delta: Int, skipHidden: Boolean = false, skipHiddenOnPage: Boolean = false) {
        val hidden = shown.filter { (skipHidden && it.hidden) || (skipHiddenOnPage && it.hiddenOnPage) }.map { it.packageName }.toSet()
        val updated = AppOrder.move(shown.map { it.packageName }, app.packageName, delta, hidden) ?: return
        prefs.edit().putString(KEY_ORDER, AppOrder.encode(updated)).apply()
        savedOrder.value = updated
    }

    private fun installed(): Flow<List<InstalledApp>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                // An icon pack that was updated or removed must be read again.
                iconPackManager.invalidateCache(i?.data?.schemeSpecificPart)
                images.evictAll()
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        trySend(Unit)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.conflate().map { query() }.flowOn(Dispatchers.IO)

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_LABELS = "labels"
        const val KEY_ICON_PACK = "icon_pack"
        const val KEY_ICONS = "icons"
        const val KEY_HIDDEN = "hidden"
        const val KEY_HIDDEN_PAGE = "hidden_page"
    }

    private fun query(): List<InstalledApp> {
        fun list(category: String) = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(category), 0
        )
        val tv = list(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvPackages = tv.map { it.activityInfo.packageName }.toSet()
        val normal = list(Intent.CATEGORY_LAUNCHER).filter { it.activityInfo.packageName !in tvPackages }
        return (tv.map { it to true } + normal.map { it to false })
            .filter { (ri, _) -> ri.activityInfo.packageName != context.packageName }
            .distinctBy { (ri, _) -> ri.activityInfo.packageName }
            .map { (ri, isTv) ->
                InstalledApp(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    isTvApp = isTv,
                    componentName = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's banner (TV apps) or icon, from the icon pack if one applies, loaded off the main thread and remembered. */
    suspend fun image(app: InstalledApp): Bitmap? {
        val cacheKey = app.packageName + "|" + app.iconKey
        images.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            val choice = savedIcons.value[app.packageName]
            val pack = savedPack.value
            // An image the user picked from a file manager.
            IconChoice.fileName(choice)?.let { name ->
                val custom = runCatching { BitmapFactory.decodeFile(File(customIconDir(), name).path) }.getOrNull()
                if (custom != null) {
                    images.put(cacheKey, custom)
                    return@withContext custom
                }
            }
            val fromPack = runCatching {
                when {
                    choice == IconChoice.SYSTEM -> null
                    choice != null -> IconChoice.parse(choice)?.let { (packPackage, drawable) ->
                        iconPackManager.loadSpecificIcon(packPackage, drawable)
                    }
                    pack != null -> iconPackManager.loadIconForApp(app.packageName, app.componentName, pack)
                    else -> null
                }?.asAndroidBitmap()
            }.getOrNull()
            if (fromPack != null) {
                images.put(cacheKey, fromPack)
                return@withContext fromPack
            }
            runCatching {
                val info = pm.getApplicationInfo(app.packageName, 0)
                val drawable = (if (app.isTvApp) info.loadBanner(pm) else null) ?: info.loadIcon(pm)
                // Banners are wider than tall; keep their shape, size icons to the card.
                val w = if (app.isTvApp) 320 else 144
                val h = if (app.isTvApp) 180 else 144
                drawable.toBitmap(w, h)
            }.getOrNull()?.also { images.put(cacheKey, it) }
        }
    }

    /** Opens the app. False when it has nothing to open (for example it was just removed). */
    fun launch(app: InstalledApp): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** The system's page for the app: Force stop, Uninstall, Clear data. */
    fun openAppInfo(app: InstalledApp): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}

/** How an app's icon is chosen, kept apart from Android so it can be tested. */
object IconChoice {
    /** This app shows its own icon, whatever icon pack is selected. */
    const val SYSTEM = "system"
    private const val PREFIX = "iconpack:"
    private const val FILE_PREFIX = "file:"

    /** An image the user picked from a file manager, saved in the app's own storage under [name]. */
    fun fromFile(name: String) = "$FILE_PREFIX$name"

    /** The saved file name of an image choice; null for anything else. */
    fun fileName(choice: String?): String? =
        choice?.takeIf { it.startsWith(FILE_PREFIX) }?.removePrefix(FILE_PREFIX)
            ?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it }

    fun fromPack(packPackage: String, drawable: String) = "$PREFIX$packPackage:$drawable"

    /** The icon pack and drawable of a pack choice; null for anything else. */
    fun parse(choice: String?): Pair<String, String>? {
        if (choice == null || !choice.startsWith(PREFIX)) return null
        val parts = choice.removePrefix(PREFIX).split(':', limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
    }

    /** Changes whenever the pack or this app's own choice does. */
    fun key(pack: String?, choice: String?) = "${pack.orEmpty()}|${choice.orEmpty()}"
}

/** The saved arrangement of apps, kept apart from Android so it can be tested. */
object AppOrder {
    fun encode(order: List<String>): String = order.joinToString("\n")
    fun decode(saved: String?): List<String> = saved?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** [apps] (A to Z) rearranged: saved packages first in their saved order, anything new after, A to Z. */
    fun apply(apps: List<InstalledApp>, order: List<String>): List<InstalledApp> {
        if (order.isEmpty()) return apps
        val rank = order.withIndex().associate { it.value to it.index }
        return apps.sortedWith(compareBy<InstalledApp> { rank[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }

    /** The whole shown order with [pkg] moved by [delta] places; null when it can't move that way. */
    fun move(shown: List<String>, pkg: String, delta: Int, hidden: Set<String> = emptySet()): List<String>? {
        val from = shown.indexOf(pkg)
        if (from < 0 || delta == 0) return null
        // Hidden apps are not in the row the user is looking at, so one place there steps over them.
        val step = if (delta > 0) 1 else -1
        var to = from
        var left = kotlin.math.abs(delta)
        while (left > 0) {
            to += step
            if (to !in shown.indices) return null
            if (shown[to] !in hidden) left--
        }
        return shown.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/** The apps hidden from the Home row, kept apart from Android so it can be tested. One package per line. */
object AppHidden {
    fun encode(hidden: Set<String>): String = AppOrder.encode(hidden.sorted())
    fun decode(saved: String?): Set<String> = saved?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    fun set(hidden: Set<String>, pkg: String, hide: Boolean): Set<String> = if (hide) hidden + pkg else hidden - pkg
}

/** The names the user gave apps, kept apart from Android so it can be tested. One line per app: package, tab, name. */
object AppLabels {
    fun encode(labels: Map<String, String>): String = labels.entries.joinToString("\n") { "${it.key}\t${it.value.replace('\n', ' ').replace('\t', ' ')}" }

    fun decode(saved: String?): Map<String, String> = saved?.lines().orEmpty().mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

    /** [labels] with [pkg] named [name] (trimmed, at most 40 characters), or with the custom name removed when blank. */
    fun set(labels: Map<String, String>, pkg: String, name: String?): Map<String, String> {
        val clean = name?.replace('\n', ' ')?.replace('\t', ' ')?.trim()?.take(40).orEmpty()
        return if (clean.isEmpty()) labels - pkg else labels + (pkg to clean)
    }
}
