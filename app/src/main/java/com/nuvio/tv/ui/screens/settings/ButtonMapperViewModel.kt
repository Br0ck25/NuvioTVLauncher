package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.InstalledAppsRepository
import com.nuvio.tv.core.apps.InstalledAppsState
import com.nuvio.tv.core.launcher.ButtonAction
import com.nuvio.tv.core.launcher.ButtonCapabilityStatus
import com.nuvio.tv.core.launcher.ButtonCaptureController
import com.nuvio.tv.core.launcher.ButtonCaptureState
import com.nuvio.tv.core.launcher.ButtonMapperPersistedState
import com.nuvio.tv.core.launcher.ButtonMapperTimingConfig
import com.nuvio.tv.core.launcher.ButtonMapping
import com.nuvio.tv.core.launcher.ButtonMappingRepository
import com.nuvio.tv.core.launcher.ButtonMappingScope
import com.nuvio.tv.core.launcher.ButtonPressType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ButtonMapperViewModel @Inject constructor(
    val repository: ButtonMappingRepository,
    val captureController: ButtonCaptureController,
    private val installedAppsRepository: InstalledAppsRepository
) : ViewModel() {

    val persistedState: StateFlow<ButtonMapperPersistedState> = repository.state
    val captureState: StateFlow<ButtonCaptureState> = captureController.state
    val installedAppsState: StateFlow<InstalledAppsState> = installedAppsRepository.state

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setMasterEnabled(enabled)
        }
    }

    fun setHomeCustomizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setHomeCustomizationEnabled(enabled)
        }
    }

    fun startButtonCapture(timeoutMs: Long = ButtonMapperTimingConfig.DEFAULT_CAPTURE_TIMEOUT_MS) {
        captureController.startCapture(timeoutMs)
    }

    fun cancelButtonCapture() {
        captureController.cancelCapture()
    }

    fun selectButtonForDraft(
        keyCode: Int,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean
    ) {
        captureController.selectButtonForDraft(
            keyCode = keyCode,
            repository = repository,
            accessibilityEnabled = accessibilityEnabled,
            nativeHomeActive = nativeHomeActive
        )
    }

    fun confirmSaveMapping(mapping: ButtonMapping) {
        viewModelScope.launch {
            repository.upsertMapping(mapping)
            captureController.cancelCapture()
        }
    }

    fun updatePressAction(
        keyCode: Int,
        pressType: ButtonPressType,
        action: ButtonAction,
        displayName: String? = null
    ) {
        viewModelScope.launch {
            repository.updatePressAction(
                keyCode = keyCode,
                pressType = pressType,
                action = action,
                displayName = displayName
            )
        }
    }

    fun clearPressAction(keyCode: Int, pressType: ButtonPressType) {
        viewModelScope.launch {
            repository.clearPressAction(keyCode, pressType)
        }
    }

    fun setMappingEnabled(keyCode: Int, enabled: Boolean) {
        viewModelScope.launch {
            repository.setMappingEnabled(keyCode, enabled)
        }
    }

    fun setMappingScope(keyCode: Int, scope: ButtonMappingScope) {
        viewModelScope.launch {
            repository.setMappingScope(keyCode, scope)
        }
    }

    fun deleteMapping(keyCode: Int) {
        viewModelScope.launch {
            repository.deleteMapping(keyCode)
        }
    }

    fun restoreButtonDefaults(keyCode: Int) {
        viewModelScope.launch {
            repository.restoreButtonDefaults(keyCode)
        }
    }

    fun restoreHomeDefaults() {
        viewModelScope.launch {
            repository.restoreHomeDefaults()
        }
    }

    fun resetAllToDefaults() {
        viewModelScope.launch {
            repository.resetAllToDefaults()
        }
    }

    fun refreshInstalledApps() {
        installedAppsRepository.refresh()
    }

    fun capabilityStatusFor(
        keyCode: Int,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean
    ): ButtonCapabilityStatus = repository.capabilityStatusFor(
        keyCode = keyCode,
        accessibilityEnabled = accessibilityEnabled,
        nativeHomeActive = nativeHomeActive
    )

    override fun onCleared() {
        captureController.cancelCapture()
        super.onCleared()
    }
}

/**
 * Formats a [ButtonAction] into a user-facing label, resolving installed app display names
 * and marking uninstalled apps clearly.
 */
internal fun formatButtonActionLabel(
    action: ButtonAction,
    installedApps: List<InstalledApp>
): String = when (action) {
    ButtonAction.None -> "Not Assigned"
    ButtonAction.RestoreDefault -> "Restore Default"
    ButtonAction.GoToTopOfHome -> "Go to Top of Home"
    ButtonAction.OpenHome -> "Open Home"
    ButtonAction.OpenSearch -> "Search"
    ButtonAction.OpenLibrary -> "Library"
    ButtonAction.OpenSettings -> "Settings"
    ButtonAction.OpenProfileSwitcher -> "Profile Switcher"
    ButtonAction.OpenApps -> "Open Apps"
    ButtonAction.OpenAppDrawer -> "Open App Drawer"
    ButtonAction.OpenLiveTv -> "Open Live TV"
    ButtonAction.OpenTvGuide -> "Open TV Guide"
    ButtonAction.MediaPlayPause -> "Play / Pause"
    ButtonAction.MediaRewind -> "Rewind"
    ButtonAction.MediaFastForward -> "Fast Forward"
    ButtonAction.MediaPrevious -> "Previous Track"
    ButtonAction.MediaNext -> "Next Track"
    ButtonAction.MediaStop -> "Stop"
    is ButtonAction.LaunchApp -> {
        val installed = installedApps.firstOrNull { it.packageName == action.packageName }
        when {
            installed != null -> "Open ${installed.displayLabel}"
            action.appLabel.isNotBlank() -> "Open ${action.appLabel} (Not installed)"
            else -> "Open ${action.packageName} (Not installed)"
        }
    }
    ButtonAction.OpenAndroidSettings -> "Open Android Settings"
    ButtonAction.OpenDisplaySettings -> "Open Display Settings"
}

internal fun formatCapabilityBadge(status: ButtonCapabilityStatus): String = when (status) {
    ButtonCapabilityStatus.AVAILABLE_GLOBALLY -> "Available Globally"
    ButtonCapabilityStatus.AVAILABLE_IN_NUVIO -> "Available in Nuvio"
    ButtonCapabilityStatus.UNKNOWN -> "Unverified on This Remote"
    ButtonCapabilityStatus.UNAVAILABLE -> "Unavailable"
    ButtonCapabilityStatus.SYSTEM_RESERVED -> "System Reserved"
}
