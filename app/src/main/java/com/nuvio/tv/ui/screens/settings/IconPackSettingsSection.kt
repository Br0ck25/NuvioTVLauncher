package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.IconPackInfo
import com.nuvio.tv.core.apps.IconPackManager
import com.nuvio.tv.core.apps.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The launcher's icon pack picker in Settings → Appearance. It has its own ViewModel, so Nuvio One's
 * ThemeSettingsViewModel and its tests stay as they are; ThemeSettingsContent only calls
 * IconPackSettingsSection() (see LAUNCHER_RULES.md).
 */

data class IconPackSettingsUiState(
    val installedIconPacks: List<IconPackInfo> = emptyList(),
    val selectedIconPackPackage: String? = null,
    val isLoadingIconPacks: Boolean = false
)

@HiltViewModel
class IconPackSettingsViewModel @Inject constructor(
    private val iconPackManager: IconPackManager,
    private val installedAppsRepository: InstalledAppsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IconPackSettingsUiState())
    val uiState: StateFlow<IconPackSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            installedAppsRepository.selectedIconPack.collectLatest { pack ->
                _uiState.update { it.copy(selectedIconPackPackage = pack) }
            }
        }
    }

    fun select(packageName: String?) = installedAppsRepository.setIconPack(packageName)

    /** Looks for installed icon packs. Only done when the picker is opened, not at start-up. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingIconPacks = true) }
            val packs = runCatching { iconPackManager.getInstalledIconPacks() }.getOrDefault(emptyList())
            _uiState.update { it.copy(installedIconPacks = packs, isLoadingIconPacks = false) }
        }
    }
}

/** The "Icon pack" card and its picker dialog. */
@Composable
fun IconPackSettingsSection(viewModel: IconPackSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showIconPackDialog by remember { mutableStateOf(false) }

    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.appearance_icon_pack_title),
        subtitle = stringResource(R.string.appearance_icon_pack_subtitle)
    ) {
        val currentPackName = remember(uiState.selectedIconPackPackage, uiState.installedIconPacks) {
            uiState.selectedIconPackPackage?.let { selected ->
                uiState.installedIconPacks.firstOrNull { it.packageName == selected }?.name ?: selected
            }
        }
        SettingsActionRow(
            title = stringResource(R.string.appearance_icon_pack_row_title),
            subtitle = stringResource(R.string.appearance_icon_pack_row_subtitle),
            value = currentPackName ?: stringResource(R.string.appearance_icon_pack_system_default),
            onClick = {
                viewModel.refresh()
                showIconPackDialog = true
            }
        )
    }

    if (showIconPackDialog) {
        IconPackPickerDialog(
            installedPacks = uiState.installedIconPacks,
            selectedPackage = uiState.selectedIconPackPackage,
            isLoading = uiState.isLoadingIconPacks,
            onSelect = { pkg ->
                viewModel.select(pkg)
                showIconPackDialog = false
            },
            onDismiss = { showIconPackDialog = false }
        )
    }
}
