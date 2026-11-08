package com.nuvio.tv.ui.screens.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.apps.IconPackEntry
import com.nuvio.tv.core.apps.IconPackInfo
import com.nuvio.tv.core.apps.IconPackManager
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.launchInstalledApp
import com.nuvio.tv.data.local.InstalledAppsPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import kotlinx.coroutines.flow.collectLatest

import com.nuvio.tv.core.apps.InstalledAppsRepository

data class AppContextMenuUiState(
    val iconPacks: List<IconPackInfo> = emptyList(),
    val isLoadingIconPacks: Boolean = false,
    val selectedPackPackage: String? = null,
    val selectedPackIcons: List<IconPackEntry> = emptyList(),
    val isLoadingPackIcons: Boolean = false,
    val activeGlobalPackPackage: String? = null
)

@HiltViewModel
class AppContextMenuViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: InstalledAppsPreferencesDataStore,
    private val iconPackManager: IconPackManager,
    private val installedAppsRepository: InstalledAppsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppContextMenuUiState())
    val uiState: StateFlow<AppContextMenuUiState> = _uiState.asStateFlow()

    init {
        loadIconPacks()
        viewModelScope.launch {
            preferencesDataStore.preferences.collectLatest { prefs ->
                _uiState.update { it.copy(activeGlobalPackPackage = prefs.selectedIconPackPackage) }
            }
        }
    }

    fun loadIconPacks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingIconPacks = true) }
            val packs = iconPackManager.getInstalledIconPacks()
            _uiState.update { it.copy(iconPacks = packs, isLoadingIconPacks = false) }
        }
    }

    fun loadPackIcons(packPackage: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedPackPackage = packPackage, isLoadingPackIcons = true) }
            val icons = iconPackManager.loadIconsForPack(packPackage)
            _uiState.update { it.copy(selectedPackIcons = icons, isLoadingPackIcons = false) }
        }
    }

    fun clearSelectedPack() {
        _uiState.update { it.copy(selectedPackPackage = null, selectedPackIcons = emptyList()) }
    }

    fun renameApp(packageName: String, newTitle: String) {
        viewModelScope.launch {
            preferencesDataStore.setCustomTitle(packageName, newTitle)
            installedAppsRepository.refresh()
        }
    }

    fun setAppIcon(packageName: String, iconRef: String?) {
        viewModelScope.launch {
            preferencesDataStore.setCustomIcon(packageName, iconRef)
            installedAppsRepository.refresh()
        }
    }

    fun applyIconPackToApp(packageName: String, iconPackPackage: String, drawableName: String) {
        viewModelScope.launch {
            preferencesDataStore.setCustomIcon(packageName, "iconpack:$iconPackPackage:$drawableName")
            installedAppsRepository.refresh()
        }
    }

    fun setGlobalIconPack(iconPackPackage: String?) {
        viewModelScope.launch {
            preferencesDataStore.setSelectedIconPack(iconPackPackage)
            installedAppsRepository.refresh()
        }
    }

    fun resetAppCustomization(packageName: String) {
        viewModelScope.launch {
            preferencesDataStore.resetAppCustomization(packageName)
            installedAppsRepository.refresh()
        }
    }

    fun openAppSettings(activityContext: Context = context, packageName: String) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                if (activityContext !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            activityContext.startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    fun uninstallApp(activityContext: Context = context, packageName: String) {
        val pm = activityContext.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
        val isNonUpdatedSystemApp = appInfo != null &&
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

        if (isNonUpdatedSystemApp) {
            android.widget.Toast.makeText(
                activityContext,
                "System apps cannot be uninstalled; opening App Info",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            openAppSettings(activityContext, packageName)
            return
        }

        val packageUri = Uri.fromParts("package", packageName, null)
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_DELETE, packageUri).apply {
                if (activityContext !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            activityContext.startActivity(intent)
            true
        }.getOrElse {
            runCatching {
                val fallbackIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri).apply {
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    if (activityContext !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                activityContext.startActivity(fallbackIntent)
                true
            }.getOrDefault(false)
        }

        if (!launched) {
            openAppSettings(activityContext, packageName)
        }
    }

    fun moveApp(packageName: String, direction: Int, availablePackages: List<String>) {
        viewModelScope.launch {
            preferencesDataStore.moveApp(packageName, direction, availablePackages)
        }
    }

    fun removeFromRow(packageName: String, availablePackages: List<String>) {
        viewModelScope.launch {
            preferencesDataStore.removeFromRow(packageName, availablePackages)
        }
    }

    fun launchApp(activityContext: Context = context, app: InstalledApp) {
        launchInstalledApp(activityContext, app)
    }
}
