@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.launcher.ButtonAction
import com.nuvio.tv.core.launcher.ButtonEventSource
import com.nuvio.tv.core.launcher.ButtonMapping
import com.nuvio.tv.core.launcher.ButtonMappingScope
import com.nuvio.tv.core.launcher.ButtonPressType
import com.nuvio.tv.core.launcher.RemoteButtonCatalog
import com.nuvio.tv.core.launcher.RemoteButtonCategory
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Main UI for `Settings → Remote & Buttons → Button Mapper`.
 *
 * Follows Nuvio TV Launcher's Settings design system (`SettingsDetailHeader`, `SettingsGroupCard`,
 * `SettingsToggleRow`, `SettingsActionRow`, `NuvioDialog`) and D-pad focus management.
 */
@Composable
internal fun ButtonMapperSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: ButtonMapperViewModel = hiltViewModel()
) {
    val persistedState by viewModel.persistedState.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val installedAppsList by viewModel.installedApps.collectAsStateWithLifecycle()
    val launcherSetup = rememberLauncherSetupState()
    val context = LocalContext.current

    var showCaptureDialog by remember { mutableStateOf(false) }
    var editingButtonKeyCode by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteMapping by remember { mutableStateOf<ButtonMapping?>(null) }
    var showResetAllConfirmation by remember { mutableStateOf(false) }

    val homeMapping = remember(persistedState.mappings, persistedState.homeCustomizationEnabled) {
        if (!persistedState.homeCustomizationEnabled) {
            ButtonMapping.defaultHomeMapping()
        } else {
            persistedState.mappingFor(KeyEvent.KEYCODE_HOME)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = "${stringResource(R.string.settings_remote_buttons)} — ${stringResource(R.string.button_mapper_title)}",
            subtitle = stringResource(R.string.settings_remote_buttons_subtitle)
        )

        // Master Controls & Global Capability Card
        SettingsGroupCard(
            title = stringResource(R.string.button_mapper_title),
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.button_mapper_master_toggle),
                subtitle = stringResource(R.string.button_mapper_master_subtitle),
                checked = persistedState.masterEnabled,
                onToggle = { viewModel.setMasterEnabled(!persistedState.masterEnabled) },
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )

            SettingsToggleRow(
                title = stringResource(R.string.button_mapper_home_customization_toggle),
                subtitle = stringResource(R.string.button_mapper_home_customization_subtitle),
                checked = persistedState.homeCustomizationEnabled,
                enabled = persistedState.masterEnabled,
                onToggle = {
                    viewModel.setHomeCustomizationEnabled(!persistedState.homeCustomizationEnabled)
                }
            )

            SettingsActionRow(
                title = stringResource(
                    if (launcherSetup.isAccessibilityEnabled) {
                        R.string.button_mapper_global_status_active
                    } else {
                        R.string.button_mapper_global_status_inactive
                    }
                ),
                subtitle = if (launcherSetup.isAccessibilityEnabled) {
                    "Configured global button mappings work both inside Nuvio and while other apps are open when Android exposes the key."
                } else {
                    "While Accessibility Service is off, button mappings work inside Nuvio. Select to open Android Accessibility Settings for global support."
                },
                value = if (launcherSetup.isAccessibilityEnabled) "Active" else "Configure",
                onClick = launcherSetup.openAccessibilitySettings
            )

            SettingsActionRow(
                title = stringResource(R.string.button_mapper_add_button),
                subtitle = stringResource(R.string.button_mapper_add_button_subtitle),
                leadingIcon = Icons.Default.Add,
                enabled = persistedState.masterEnabled,
                onClick = {
                    viewModel.refreshInstalledApps()
                    viewModel.startButtonCapture()
                    showCaptureDialog = true
                }
            )
        }

        // Configured Remote Buttons Card
        SettingsGroupCard(
            title = "Configured Remote Buttons",
            modifier = Modifier.fillMaxWidth()
        ) {
            // If the user deleted the Home mapping, show a convenient row to restore it.
            val hasHomeInList = persistedState.mappings.any { it.keyCode == KeyEvent.KEYCODE_HOME }
            if (!hasHomeInList) {
                SettingsActionRow(
                    title = "Home (Default Active)",
                    subtitle = if (persistedState.homeCustomizationEnabled) {
                        "Home mapping was removed. Select to restore default Home button assignments."
                    } else {
                        "Single: Go to Top of Home • Double: Profile Switcher • Long: Not Assigned"
                    },
                    value = "Restore",
                    leadingIcon = Icons.Default.Home,
                    onClick = { viewModel.restoreHomeDefaults() }
                )
            }

            persistedState.mappings.forEach { mapping ->
                val effectiveMapping = if (mapping.isHomeButton() && !persistedState.homeCustomizationEnabled) {
                    ButtonMapping.defaultHomeMapping()
                } else {
                    mapping
                }
                val capabilityStatus = viewModel.capabilityStatusFor(
                    keyCode = effectiveMapping.keyCode,
                    accessibilityEnabled = launcherSetup.isAccessibilityEnabled,
                    nativeHomeActive = launcherSetup.isDefaultHome
                )
                val statusLabel = when {
                    !persistedState.masterEnabled -> "Mapper Disabled"
                    mapping.isHomeButton() && !persistedState.homeCustomizationEnabled -> "Factory Default"
                    !effectiveMapping.enabled -> "Disabled"
                    else -> formatCapabilityBadge(capabilityStatus)
                }
                val singleLabel = formatButtonActionLabel(
                    effectiveMapping.singleAction,
                    installedAppsList
                )
                val doubleLabel = formatButtonActionLabel(
                    effectiveMapping.doubleAction,
                    installedAppsList
                )
                val longLabel = formatButtonActionLabel(
                    effectiveMapping.longAction,
                    installedAppsList
                )

                SettingsActionRow(
                    title = effectiveMapping.displayName,
                    subtitle = "Single: $singleLabel • Double: $doubleLabel • Long: $longLabel",
                    value = statusLabel,
                    leadingIcon = iconForRemoteButton(effectiveMapping.keyCode),
                    enabled = persistedState.masterEnabled,
                    onClick = {
                        viewModel.refreshInstalledApps()
                        editingButtonKeyCode = effectiveMapping.keyCode
                    }
                )
            }
        }

        // Reset & Maintenance Card
        SettingsGroupCard(
            title = "Maintenance",
            modifier = Modifier.fillMaxWidth()
        ) {
            if (homeMapping == null || !homeMapping.matchesFactoryHomeDefaults() || !persistedState.homeCustomizationEnabled) {
                SettingsActionRow(
                    title = stringResource(R.string.button_mapper_restore_home_defaults),
                    subtitle = "Reset Home to Single: Go to Top of Home, Double: Profile Switcher, Long: Not Assigned.",
                    leadingIcon = Icons.Default.Restore,
                    onClick = { viewModel.restoreHomeDefaults() }
                )
            }

            SettingsActionRow(
                title = stringResource(R.string.button_mapper_reset_defaults),
                subtitle = stringResource(R.string.button_mapper_reset_defaults_subtitle),
                leadingIcon = Icons.Default.Restore,
                onClick = { showResetAllConfirmation = true }
            )
        }
    }

    // Add Button Mapping / Capture Dialog
    if (showCaptureDialog) {
        ButtonCaptureDialog(
            captureState = captureState,
            existingMappings = persistedState.mappings,
            installedApps = installedAppsList,
            onPhysicalKeyEvent = { nativeEvent ->
                viewModel.captureController.onKeyEvent(
                    keyCode = nativeEvent.keyCode,
                    action = nativeEvent.action,
                    repeatCount = nativeEvent.repeatCount,
                    source = ButtonEventSource.FOREGROUND,
                    repository = viewModel.repository,
                    accessibilityEnabled = launcherSetup.isAccessibilityEnabled,
                    nativeHomeActive = launcherSetup.isDefaultHome
                )
            },
            onRetryCapture = { viewModel.startButtonCapture() },
            onSelectKnownButton = { keyCode ->
                viewModel.selectButtonForDraft(
                    keyCode = keyCode,
                    accessibilityEnabled = launcherSetup.isAccessibilityEnabled,
                    nativeHomeActive = launcherSetup.isDefaultHome
                )
            },
            onConfirmSave = { newMapping ->
                viewModel.confirmSaveMapping(newMapping)
                showCaptureDialog = false
            },
            onRefreshApps = viewModel::refreshInstalledApps,
            onCancel = {
                viewModel.cancelButtonCapture()
                showCaptureDialog = false
            }
        )
    }

    // Edit Button Mapping Dialog
    val currentEditingKeyCode = editingButtonKeyCode
    if (currentEditingKeyCode != null) {
        val mappingToEdit = persistedState.mappingFor(currentEditingKeyCode)
            ?: if (currentEditingKeyCode == KeyEvent.KEYCODE_HOME) {
                ButtonMapping.defaultHomeMapping()
            } else {
                null
            }
        if (mappingToEdit != null) {
            val status = viewModel.capabilityStatusFor(
                keyCode = mappingToEdit.keyCode,
                accessibilityEnabled = launcherSetup.isAccessibilityEnabled,
                nativeHomeActive = launcherSetup.isDefaultHome
            )
            EditButtonMappingDialog(
                mapping = mappingToEdit,
                capabilityBadge = formatCapabilityBadge(status),
                installedApps = installedAppsList,
                onUpdateAction = { pressType, action ->
                    if (mappingToEdit.isHomeButton() && !persistedState.homeCustomizationEnabled) {
                        viewModel.setHomeCustomizationEnabled(true)
                    }
                    viewModel.updatePressAction(
                        keyCode = mappingToEdit.keyCode,
                        pressType = pressType,
                        action = action,
                        displayName = mappingToEdit.displayName
                    )
                },
                onClearAction = { pressType ->
                    viewModel.clearPressAction(mappingToEdit.keyCode, pressType)
                },
                onToggleEnabled = { enabled ->
                    viewModel.setMappingEnabled(mappingToEdit.keyCode, enabled)
                },
                onCycleScope = {
                    val nextScope = when (mappingToEdit.scope) {
                        ButtonMappingScope.FOREGROUND_AND_GLOBAL -> ButtonMappingScope.FOREGROUND_ONLY
                        ButtonMappingScope.FOREGROUND_ONLY -> ButtonMappingScope.GLOBAL_ONLY
                        ButtonMappingScope.GLOBAL_ONLY -> ButtonMappingScope.FOREGROUND_AND_GLOBAL
                    }
                    viewModel.setMappingScope(mappingToEdit.keyCode, nextScope)
                },
                onRestoreDefault = {
                    viewModel.restoreButtonDefaults(mappingToEdit.keyCode)
                },
                onRequestDelete = {
                    editingButtonKeyCode = null
                    pendingDeleteMapping = mappingToEdit
                },
                onRefreshApps = viewModel::refreshInstalledApps,
                onDismiss = { editingButtonKeyCode = null }
            )
        } else {
            editingButtonKeyCode = null
        }
    }

    // Delete Mapping Confirmation Dialog
    val deleteTarget = pendingDeleteMapping
    if (deleteTarget != null) {
        ConfirmButtonMapperActionDialog(
            title = stringResource(R.string.button_mapper_delete_confirm_title),
            message = stringResource(R.string.button_mapper_delete_confirm_message, deleteTarget.displayName),
            confirmText = "Delete Mapping",
            onConfirm = {
                viewModel.deleteMapping(deleteTarget.keyCode)
                pendingDeleteMapping = null
            },
            onDismiss = { pendingDeleteMapping = null }
        )
    }

    // Reset All Confirmation Dialog
    if (showResetAllConfirmation) {
        ConfirmButtonMapperActionDialog(
            title = stringResource(R.string.button_mapper_reset_confirm_title),
            message = stringResource(R.string.button_mapper_reset_confirm_message),
            confirmText = stringResource(R.string.button_mapper_reset_defaults),
            onConfirm = {
                viewModel.resetAllToDefaults()
                showResetAllConfirmation = false
            },
            onDismiss = { showResetAllConfirmation = false }
        )
    }
}

/**
 * Dialog for editing an existing [ButtonMapping]: changing or clearing single/double/long actions,
 * changing scope, enabling/disabling the mapping, restoring defaults, or deleting the mapping.
 */
@Composable
private fun EditButtonMappingDialog(
    mapping: ButtonMapping,
    capabilityBadge: String,
    installedApps: List<InstalledApp>,
    onUpdateAction: (ButtonPressType, ButtonAction) -> Unit,
    onClearAction: (ButtonPressType) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onCycleScope: () -> Unit,
    onRestoreDefault: () -> Unit,
    onRequestDelete: () -> Unit,
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectingPressType by remember { mutableStateOf<ButtonPressType?>(null) }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectingPressType) {
        if (selectingPressType == null) {
            firstFocusRequester.requestFocusAfterFrames()
        }
    }

    val activePressType = selectingPressType
    if (activePressType != null) {
        ButtonActionPickerDialog(
            buttonName = mapping.displayName,
            pressType = activePressType,
            currentAction = mapping.actionFor(activePressType),
            keyCode = mapping.keyCode,
            installedApps = installedApps,
            onSelectAction = { action ->
                onUpdateAction(activePressType, action)
                selectingPressType = null
            },
            onRefreshApps = onRefreshApps,
            onDismiss = { selectingPressType = null }
        )
        return
    }

    val scopeLabel = when (mapping.scope) {
        ButtonMappingScope.FOREGROUND_AND_GLOBAL -> "In Nuvio & Globally"
        ButtonMappingScope.FOREGROUND_ONLY -> "Only While Nuvio is Open"
        ButtonMappingScope.GLOBAL_ONLY -> "Only Outside Nuvio (Global)"
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Edit ${mapping.displayName}",
        subtitle = "KeyCode ${mapping.keyCode} • $capabilityBadge",
        width = 600.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 390.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            item(key = "edit_single") {
                DialogOptionCard(
                    title = "Single Click",
                    subtitle = formatButtonActionLabel(mapping.singleAction, installedApps),
                    trailing = "Change",
                    modifier = Modifier.focusRequester(firstFocusRequester),
                    onClick = { selectingPressType = ButtonPressType.SINGLE_PRESS }
                )
            }
            if (mapping.singleAction != ButtonAction.None) {
                item(key = "clear_single") {
                    DialogOptionCard(
                        title = "Clear Single Click Assignment",
                        subtitle = "Set Single Click to Not Assigned",
                        onClick = { onClearAction(ButtonPressType.SINGLE_PRESS) }
                    )
                }
            }

            item(key = "edit_double") {
                DialogOptionCard(
                    title = "Double Click",
                    subtitle = formatButtonActionLabel(mapping.doubleAction, installedApps),
                    trailing = "Change",
                    onClick = { selectingPressType = ButtonPressType.DOUBLE_PRESS }
                )
            }
            if (mapping.doubleAction != ButtonAction.None) {
                item(key = "clear_double") {
                    DialogOptionCard(
                        title = "Clear Double Click Assignment",
                        subtitle = "Set Double Click to Not Assigned",
                        onClick = { onClearAction(ButtonPressType.DOUBLE_PRESS) }
                    )
                }
            }

            item(key = "edit_long") {
                DialogOptionCard(
                    title = "Long Press",
                    subtitle = formatButtonActionLabel(mapping.longAction, installedApps),
                    trailing = "Change",
                    onClick = { selectingPressType = ButtonPressType.LONG_PRESS }
                )
            }
            if (mapping.longAction != ButtonAction.None) {
                item(key = "clear_long") {
                    DialogOptionCard(
                        title = "Clear Long Press Assignment",
                        subtitle = "Set Long Press to Not Assigned",
                        onClick = { onClearAction(ButtonPressType.LONG_PRESS) }
                    )
                }
            }

            item(key = "edit_scope") {
                DialogOptionCard(
                    title = "Active Scope",
                    subtitle = scopeLabel,
                    trailing = "Cycle",
                    onClick = onCycleScope
                )
            }

            item(key = "edit_enabled") {
                DialogOptionCard(
                    title = if (mapping.enabled) "Disable Mapping" else "Enable Mapping",
                    subtitle = if (mapping.enabled) {
                        "Temporarily disable custom actions for ${mapping.displayName}"
                    } else {
                        "Re-enable custom actions for ${mapping.displayName}"
                    },
                    trailing = if (mapping.enabled) "Enabled" else "Disabled",
                    onClick = { onToggleEnabled(!mapping.enabled) }
                )
            }

            item(key = "edit_restore") {
                DialogOptionCard(
                    title = if (mapping.isHomeButton()) {
                        "Restore Default Home Assignments"
                    } else {
                        "Restore Default Behavior"
                    },
                    subtitle = if (mapping.isHomeButton()) {
                        "Single: Go to Top of Home • Double: Profile Switcher • Long: Not Assigned"
                    } else {
                        "Clear all custom actions for ${mapping.displayName}"
                    },
                    onClick = onRestoreDefault
                )
            }

            item(key = "edit_delete") {
                DialogOptionCard(
                    title = "Delete Mapping",
                    subtitle = "Remove ${mapping.displayName} from the configured buttons list",
                    onClick = onRequestDelete
                )
            }

            item(key = "edit_done") {
                DialogOptionCard(
                    title = "Done",
                    subtitle = "Return to Button Mapper",
                    highlighted = true,
                    onClick = onDismiss
                )
            }
        }
    }
}

private fun iconForRemoteButton(keyCode: Int): ImageVector {
    val descriptor = RemoteButtonCatalog.descriptorFor(keyCode)
    return when {
        keyCode == KeyEvent.KEYCODE_HOME -> Icons.Default.Home
        keyCode == KeyEvent.KEYCODE_BACK -> Icons.Default.ArrowBack
        keyCode == KeyEvent.KEYCODE_MENU -> Icons.Default.Menu
        keyCode == KeyEvent.KEYCODE_SEARCH -> Icons.Default.Search
        keyCode == KeyEvent.KEYCODE_VOICE_ASSIST -> Icons.Default.Mic
        keyCode == KeyEvent.KEYCODE_SETTINGS -> Icons.Default.Settings
        keyCode == KeyEvent.KEYCODE_INFO -> Icons.Default.Info
        descriptor.category == RemoteButtonCategory.MEDIA -> Icons.Default.PlayArrow
        descriptor.category == RemoteButtonCategory.APP_SHORTCUT -> Icons.Default.Apps
        descriptor.category == RemoteButtonCategory.TV_CONTROL -> Icons.Default.LiveTv
        descriptor.category == RemoteButtonCategory.COLORED -> Icons.Default.Circle
        else -> Icons.Default.SettingsRemote
    }
}
