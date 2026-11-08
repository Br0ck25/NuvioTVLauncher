package com.nuvio.tv.core.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContextCompat
import com.nuvio.tv.data.local.InstalledAppsPreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

data class InstalledAppsState(
    val apps: List<InstalledApp> = emptyList(),
    val order: List<String> = emptyList(),
    val isLoading: Boolean = true
)

/** Package installs/updates arrive in bursts; coalesce them into one refresh. */
private const val PACKAGE_CHANGE_DEBOUNCE_MS = 300L

/** Device-wide package discovery; ordering and row visibility are profile preferences. */
@OptIn(FlowPreview::class)
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: InstalledAppsPreferencesDataStore,
    private val iconPackManager: IconPackManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val decodeCache = InstalledAppsDecodeCache()

    val state = combine(
        merge(
            flowOf(Unit), // initial load, not debounced
            packageChanges().debounce(PACKAGE_CHANGE_DEBOUNCE_MS),
            refreshRequests
        ).mapLatest { queryInstalledApps(context, decodeCache) },
        preferencesDataStore.preferences
    ) { baseApps, prefs ->
        val customizedApps = baseApps.map { app ->
            val customTitle = prefs.customTitles[app.packageName]
            val iconRef = prefs.customIcons[app.packageName]
            val customIcon: ImageBitmap? = when {
                iconRef == "system_banner" -> app.bannerBitmap ?: app.icon
                iconRef == "system_icon" -> app.squareBitmap ?: app.icon
                iconRef?.startsWith("iconpack:") == true -> {
                    val parts = iconRef.removePrefix("iconpack:").split(":")
                    if (parts.size >= 2) {
                        iconPackManager.loadSpecificIcon(parts[0], parts[1])
                    } else null
                }
                prefs.selectedIconPackPackage != null -> {
                    iconPackManager.loadIconForApp(app.packageName, app.componentName, prefs.selectedIconPackPackage)
                }
                else -> null
            }
            app.copy(
                customLabel = customTitle,
                customIcon = customIcon
            )
        }
        InstalledAppsState(apps = customizedApps, isLoading = false)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, InstalledAppsState())

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    private fun packageChanges() = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart
                iconPackManager.invalidateCache(pkg)
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}
