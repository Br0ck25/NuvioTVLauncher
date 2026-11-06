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

data class AppContextMenuUiState(
    val iconPacks: List<IconPackInfo> = emptyList(),
    val isLoadingIconPacks: Boolean = false,
    val selectedPackPackage: String? = null,
    val selectedPackIcons: List<IconPackEntry> = emptyList(),
    val isLoadingPackIcons: Boolean = false
)

@HiltViewModel
class AppContextMenuViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: InstalledAppsPreferencesDataStore,
    private val iconPackManager: IconPackManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppContextMenuUiState())
    val uiState: StateFlow<AppContextMenuUiState> = _uiState.asStateFlow()

    init {
        loadIconPacks()
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
        }
    }

    fun setAppIcon(packageName: String, iconRef: String?) {
        viewModelScope.launch {
            preferencesDataStore.setCustomIcon(packageName, iconRef)
        }
    }

    fun applyIconPackToApp(packageName: String, iconPackPackage: String, drawableName: String) {
        viewModelScope.launch {
            preferencesDataStore.setCustomIcon(packageName, "iconpack:$iconPackPackage:$drawableName")
        }
    }

    fun resetAppCustomization(packageName: String) {
        viewModelScope.launch {
            preferencesDataStore.resetAppCustomization(packageName)
        }
    }

    fun openAppSettings(packageName: String) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun uninstallApp(packageName: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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

    fun launchApp(app: InstalledApp) {
        launchInstalledApp(context, app)
    }
}
