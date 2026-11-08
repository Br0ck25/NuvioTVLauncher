@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.launcher.ButtonAction
import com.nuvio.tv.core.launcher.ButtonActionCategory
import com.nuvio.tv.core.launcher.ButtonCaptureState
import com.nuvio.tv.core.launcher.ButtonMapping
import com.nuvio.tv.core.launcher.ButtonMappingScope
import com.nuvio.tv.core.launcher.ButtonPressType
import com.nuvio.tv.core.launcher.CapturedButtonInfo
import com.nuvio.tv.core.launcher.RemoteButtonCatalog
import com.nuvio.tv.core.launcher.RemoteButtonCategory
import com.nuvio.tv.core.launcher.RemoteButtonDescriptor
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Dialog for adding a new remote button mapping by capturing a physical key press (or selecting
 * from known remote buttons), inspecting its availability in Nuvio and globally, and configuring
 * its Single, Double, and Long press actions before saving.
 */
@Composable
internal fun ButtonCaptureDialog(
    captureState: ButtonCaptureState,
    existingMappings: List<ButtonMapping>,
    installedApps: List<InstalledApp>,
    onPhysicalKeyEvent: (AndroidKeyEvent) -> Boolean,
    onRetryCapture: () -> Unit,
    onSelectKnownButton: (Int) -> Unit,
    onConfirmSave: (ButtonMapping) -> Unit,
    onRefreshApps: () -> Unit,
    onCancel: () -> Unit
) {
    var showKnownButtonPicker by remember { mutableStateOf(false) }

    when (captureState) {
        ButtonCaptureState.Idle,
        is ButtonCaptureState.Listening -> {
            val remainingSeconds = (captureState as? ButtonCaptureState.Listening)?.remainingSeconds ?: 10
            val cancelFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                cancelFocusRequester.requestFocusAfterFrames()
            }

            if (showKnownButtonPicker) {
                KnownRemoteButtonPickerDialog(
                    onSelectButton = { keyCode ->
                        showKnownButtonPicker = false
                        onSelectKnownButton(keyCode)
                    },
                    onDismiss = { showKnownButtonPicker = false }
                )
            } else {
                NuvioDialog(
                    onDismiss = onCancel,
                    title = "Add Button Mapping",
                    subtitle = "Press the physical remote button you want to configure.",
                    width = 560.dp,
                    suppressFirstKeyUp = false
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { keyEvent ->
                                onPhysicalKeyEvent(keyEvent.nativeKeyEvent)
                            },
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                                .background(NuvioTheme.colors.BackgroundCard)
                                .border(
                                    NuvioTheme.spacing.hairline,
                                    NuvioTheme.colors.Border,
                                    RoundedCornerShape(NuvioTheme.radii.md)
                                )
                                .padding(NuvioTheme.spacing.lg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SettingsRemote,
                                    contentDescription = null,
                                    tint = NuvioTheme.colors.FocusRing,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "Listening for remote button press… (${remainingSeconds}s)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NuvioTheme.colors.TextPrimary
                                )
                                Text(
                                    text = "D-pad, OK, and Back navigate this dialog. Press any other remote button to detect it, or choose from known buttons below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }
                        }

                        DialogOptionCard(
                            title = "Choose from Known Remote Buttons",
                            subtitle = "Select Home, Back, Menu, Media, Netflix, YouTube, Guide, Colored, or App buttons manually",
                            onClick = { showKnownButtonPicker = true }
                        )

                        DialogOptionCard(
                            title = "Cancel",
                            subtitle = "Close without adding a button mapping",
                            modifier = Modifier.focusRequester(cancelFocusRequester),
                            onClick = onCancel
                        )
                    }
                }
            }
        }

        ButtonCaptureState.TimedOut -> {
            val retryFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                retryFocusRequester.requestFocusAfterFrames()
            }

            if (showKnownButtonPicker) {
                KnownRemoteButtonPickerDialog(
                    onSelectButton = { keyCode ->
                        showKnownButtonPicker = false
                        onSelectKnownButton(keyCode)
                    },
                    onDismiss = { showKnownButtonPicker = false }
                )
            } else {
                NuvioDialog(
                    onDismiss = onCancel,
                    title = "No Button Detected",
                    subtitle = "No key event was received before the capture timeout expired.",
                    width = 560.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                                .background(NuvioTheme.colors.BackgroundCard)
                                .border(
                                    NuvioTheme.spacing.hairline,
                                    NuvioTheme.colors.Border,
                                    RoundedCornerShape(NuvioTheme.radii.md)
                                )
                                .padding(NuvioTheme.spacing.md)
                        ) {
                            Text(
                                text = "If you pressed a branded shortcut button, your TV or remote firmware may reserve that key at the system level and not expose it to Android apps. Enabling Nuvio's Accessibility Service allows global key filtering for buttons that Android exposes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }

                        DialogOptionCard(
                            title = "Try Detecting Again",
                            subtitle = "Start another 10-second button capture window",
                            modifier = Modifier.focusRequester(retryFocusRequester),
                            onClick = onRetryCapture
                        )

                        DialogOptionCard(
                            title = "Choose from Known Remote Buttons",
                            subtitle = "Pick a standard Android TV remote button from the catalog",
                            onClick = { showKnownButtonPicker = true }
                        )

                        DialogOptionCard(
                            title = "Cancel",
                            subtitle = "Return to Button Mapper",
                            onClick = onCancel
                        )
                    }
                }
            }
        }

        is ButtonCaptureState.Captured -> {
            CapturedButtonConfigurationDialog(
                info = captureState.info,
                existingMapping = existingMappings.firstOrNull { it.keyCode == captureState.info.keyCode },
                installedApps = installedApps,
                onRetryCapture = onRetryCapture,
                onConfirmSave = onConfirmSave,
                onRefreshApps = onRefreshApps,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun CapturedButtonConfigurationDialog(
    info: CapturedButtonInfo,
    existingMapping: ButtonMapping?,
    installedApps: List<InstalledApp>,
    onRetryCapture: () -> Unit,
    onConfirmSave: (ButtonMapping) -> Unit,
    onRefreshApps: () -> Unit,
    onCancel: () -> Unit
) {
    val initialDraft = remember(info.keyCode, existingMapping) {
        existingMapping ?: if (info.keyCode == AndroidKeyEvent.KEYCODE_HOME) {
            ButtonMapping.defaultHomeMapping()
        } else {
            ButtonMapping(
                keyCode = info.keyCode,
                displayName = info.descriptor.displayName,
                singleAction = ButtonAction.None,
                doubleAction = ButtonAction.None,
                longAction = ButtonAction.None,
                enabled = true,
                scope = ButtonMappingScope.FOREGROUND_AND_GLOBAL
            )
        }
    }

    var draftMapping by remember(info.keyCode) { mutableStateOf(initialDraft) }
    var editingPressType by remember { mutableStateOf<ButtonPressType?>(null) }
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(info.keyCode) {
        primaryFocusRequester.requestFocusAfterFrames()
    }

    val activePressType = editingPressType
    if (activePressType != null) {
        ButtonActionPickerDialog(
            buttonName = draftMapping.displayName,
            pressType = activePressType,
            currentAction = draftMapping.actionFor(activePressType),
            keyCode = draftMapping.keyCode,
            installedApps = installedApps,
            onSelectAction = { chosen ->
                val resolved = if (chosen == ButtonAction.RestoreDefault) {
                    ButtonMapping.defaultActionFor(draftMapping.keyCode, activePressType)
                } else {
                    chosen
                }
                draftMapping = draftMapping.withAction(activePressType, resolved)
                editingPressType = null
            },
            onRefreshApps = onRefreshApps,
            onDismiss = { editingPressType = null }
        )
        return
    }

    NuvioDialog(
        onDismiss = onCancel,
        title = if (info.isSystemReserved) "System-Reserved Button" else "Configure ${draftMapping.displayName}",
        subtitle = "Key code: ${info.keyCodeLabel} • Category: ${formatCategoryName(info.descriptor.category)}",
        width = 600.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            // Capability & diagnostic summary box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard)
                    .border(
                        NuvioTheme.spacing.hairline,
                        NuvioTheme.colors.Border,
                        RoundedCornerShape(NuvioTheme.radii.md)
                    )
                    .padding(NuvioTheme.spacing.md)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Status: ${formatCapabilityBadge(info.capabilityStatus)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Available in Nuvio: ${if (info.availableInNuvio) "Yes" else "Unverified"} • " +
                            "Available Globally: ${if (info.availableGlobally) "Yes" else "Requires Accessibility / Firmware Support"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    info.descriptor.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            if (info.isSystemReserved) {
                Text(
                    text = "Volume, Power, and D-pad navigation keys remain system-controlled by default so normal TV operation and remote navigation are never disrupted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                DialogOptionCard(
                    title = "Detect Another Button",
                    subtitle = "Press a different remote button",
                    modifier = Modifier.focusRequester(primaryFocusRequester),
                    onClick = onRetryCapture
                )
                DialogOptionCard(
                    title = "Cancel",
                    subtitle = "Close without saving",
                    onClick = onCancel
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    item {
                        DialogOptionCard(
                            title = "Single Click",
                            subtitle = formatButtonActionLabel(draftMapping.singleAction, installedApps),
                            trailing = "Edit",
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            onClick = { editingPressType = ButtonPressType.SINGLE_PRESS }
                        )
                    }
                    item {
                        DialogOptionCard(
                            title = "Double Click",
                            subtitle = formatButtonActionLabel(draftMapping.doubleAction, installedApps),
                            trailing = "Edit",
                            onClick = { editingPressType = ButtonPressType.DOUBLE_PRESS }
                        )
                    }
                    item {
                        DialogOptionCard(
                            title = "Long Press",
                            subtitle = formatButtonActionLabel(draftMapping.longAction, installedApps),
                            trailing = "Edit",
                            onClick = { editingPressType = ButtonPressType.LONG_PRESS }
                        )
                    }
                    item {
                        DialogOptionCard(
                            title = "Save Mapping",
                            subtitle = "Save ${draftMapping.displayName} assignments to Button Mapper",
                            trailing = "Confirm",
                            highlighted = true,
                            onClick = { onConfirmSave(draftMapping) }
                        )
                    }
                    item {
                        DialogOptionCard(
                            title = "Detect Another Button",
                            subtitle = "Listen for a different physical button press",
                            onClick = onRetryCapture
                        )
                    }
                    item {
                        DialogOptionCard(
                            title = "Cancel",
                            subtitle = "Discard changes and close",
                            onClick = onCancel
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun KnownRemoteButtonPickerDialog(
    onSelectButton: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    val groupedButtons = remember {
        RemoteButtonCatalog.knownButtons.groupBy { it.category }
    }

    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Select Remote Button",
        subtitle = "Choose a supported remote button category.",
        width = 580.dp
    ) {
        var attachedFirstFocus = false
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            groupedButtons.forEach { (category, buttons) ->
                item(key = "header_${category.name}") {
                    Text(
                        text = formatCategoryName(category),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(
                    items = buttons,
                    key = { descriptor: RemoteButtonDescriptor -> descriptor.keyCode }
                ) { descriptor ->
                    val modifier = if (!attachedFirstFocus) {
                        attachedFirstFocus = true
                        Modifier.focusRequester(firstFocusRequester)
                    } else {
                        Modifier
                    }
                    DialogOptionCard(
                        title = descriptor.displayName,
                        subtitle = descriptor.note ?: "KeyCode ${descriptor.keyCode}",
                        trailing = "#${descriptor.keyCode}",
                        modifier = modifier,
                        onClick = { onSelectButton(descriptor.keyCode) }
                    )
                }
            }
        }
    }
}

/**
 * Categorized action picker dialog for assigning an action to Single Click, Double Click, or Long Press.
 */
@Composable
internal fun ButtonActionPickerDialog(
    buttonName: String,
    pressType: ButtonPressType,
    currentAction: ButtonAction,
    keyCode: Int,
    installedApps: List<InstalledApp>,
    onSelectAction: (ButtonAction) -> Unit,
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit
) {
    var showAppPicker by remember { mutableStateOf(false) }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showAppPicker) {
        if (!showAppPicker) {
            firstFocusRequester.requestFocusAfterFrames()
        }
    }

    if (showAppPicker) {
        InstalledAppSelectorDialog(
            installedApps = installedApps,
            selectedPackageName = (currentAction as? ButtonAction.LaunchApp)?.packageName,
            onSelectApp = { app ->
                showAppPicker = false
                onSelectAction(
                    ButtonAction.LaunchApp(
                        packageName = app.packageName,
                        appLabel = app.displayLabel
                    )
                )
            },
            onRefreshApps = onRefreshApps,
            onDismiss = { showAppPicker = false }
        )
        return
    }

    val pressTitle = when (pressType) {
        ButtonPressType.SINGLE_PRESS -> "Single Click"
        ButtonPressType.DOUBLE_PRESS -> "Double Click"
        ButtonPressType.LONG_PRESS -> "Long Press"
    }

    val defaultAction = remember(keyCode, pressType) {
        ButtonMapping.defaultActionFor(keyCode, pressType)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "$buttonName — $pressTitle",
        subtitle = "Select an action for this button gesture.",
        width = 600.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 390.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            // Other (Do Nothing / Restore Default)
            item(key = "cat_other") {
                CategorySectionLabel("Other")
            }
            item(key = "act_none") {
                DialogOptionCard(
                    title = "Do Nothing (Not Assigned)",
                    subtitle = "Leave this press type unassigned",
                    selected = currentAction == ButtonAction.None,
                    modifier = Modifier.focusRequester(firstFocusRequester),
                    onClick = { onSelectAction(ButtonAction.None) }
                )
            }
            item(key = "act_restore_default") {
                DialogOptionCard(
                    title = "Restore Default",
                    subtitle = "Reset to default (${formatButtonActionLabel(defaultAction, installedApps)})",
                    onClick = { onSelectAction(ButtonAction.RestoreDefault) }
                )
            }

            // Nuvio Navigation
            item(key = "cat_nuvio_nav") {
                CategorySectionLabel("Nuvio Navigation")
            }
            item(key = "act_top_home") {
                DialogOptionCard(
                    title = "Go to Top of Home",
                    subtitle = "Navigate to the Nuvio Home screen and scroll to the very top",
                    selected = currentAction == ButtonAction.GoToTopOfHome,
                    onClick = { onSelectAction(ButtonAction.GoToTopOfHome) }
                )
            }
            item(key = "act_open_home") {
                DialogOptionCard(
                    title = "Open Home",
                    subtitle = "Open the Nuvio Home screen",
                    selected = currentAction == ButtonAction.OpenHome,
                    onClick = { onSelectAction(ButtonAction.OpenHome) }
                )
            }
            item(key = "act_search") {
                DialogOptionCard(
                    title = "Search",
                    subtitle = "Open Nuvio Search",
                    selected = currentAction == ButtonAction.OpenSearch,
                    onClick = { onSelectAction(ButtonAction.OpenSearch) }
                )
            }
            item(key = "act_library") {
                DialogOptionCard(
                    title = "Library",
                    subtitle = "Open Nuvio Library",
                    selected = currentAction == ButtonAction.OpenLibrary,
                    onClick = { onSelectAction(ButtonAction.OpenLibrary) }
                )
            }
            item(key = "act_settings") {
                DialogOptionCard(
                    title = "Settings",
                    subtitle = "Open Nuvio Settings",
                    selected = currentAction == ButtonAction.OpenSettings,
                    onClick = { onSelectAction(ButtonAction.OpenSettings) }
                )
            }

            // Profiles
            item(key = "cat_profiles") {
                CategorySectionLabel("Profiles")
            }
            item(key = "act_profile_switcher") {
                DialogOptionCard(
                    title = "Open Profile Switcher",
                    subtitle = "Switch between Nuvio user profiles",
                    selected = currentAction == ButtonAction.OpenProfileSwitcher,
                    onClick = { onSelectAction(ButtonAction.OpenProfileSwitcher) }
                )
            }

            // Launcher
            item(key = "cat_launcher") {
                CategorySectionLabel("Launcher")
            }
            item(key = "act_open_apps") {
                DialogOptionCard(
                    title = "Open Apps",
                    subtitle = "Open the Nuvio installed applications screen",
                    selected = currentAction == ButtonAction.OpenApps,
                    onClick = { onSelectAction(ButtonAction.OpenApps) }
                )
            }
            item(key = "act_open_app_drawer") {
                DialogOptionCard(
                    title = "Open App Drawer",
                    subtitle = "Open the Nuvio full application drawer",
                    selected = currentAction == ButtonAction.OpenAppDrawer,
                    onClick = { onSelectAction(ButtonAction.OpenAppDrawer) }
                )
            }

            // Live TV
            item(key = "cat_live_tv") {
                CategorySectionLabel("Live TV")
            }
            item(key = "act_live_tv") {
                DialogOptionCard(
                    title = "Open Live TV",
                    subtitle = "Open Nuvio Live TV channels",
                    selected = currentAction == ButtonAction.OpenLiveTv,
                    onClick = { onSelectAction(ButtonAction.OpenLiveTv) }
                )
            }
            item(key = "act_tv_guide") {
                DialogOptionCard(
                    title = "Open TV Guide",
                    subtitle = "Open the Nuvio Live TV EPG Guide",
                    selected = currentAction == ButtonAction.OpenTvGuide,
                    onClick = { onSelectAction(ButtonAction.OpenTvGuide) }
                )
            }

            // Applications
            item(key = "cat_applications") {
                CategorySectionLabel("Applications")
            }
            item(key = "act_launch_app") {
                val appSubtitle = if (currentAction is ButtonAction.LaunchApp) {
                    "Currently selected: ${formatButtonActionLabel(currentAction, installedApps)}"
                } else {
                    "Choose any installed application on this device"
                }
                DialogOptionCard(
                    title = "Launch Installed Application…",
                    subtitle = appSubtitle,
                    selected = currentAction is ButtonAction.LaunchApp,
                    trailing = "Browse",
                    onClick = {
                        onRefreshApps()
                        showAppPicker = true
                    }
                )
            }

            // Media
            item(key = "cat_media") {
                CategorySectionLabel("Media")
            }
            item(key = "act_media_play_pause") {
                DialogOptionCard(
                    title = "Play / Pause",
                    subtitle = "Send Play/Pause to the active media session",
                    selected = currentAction == ButtonAction.MediaPlayPause,
                    onClick = { onSelectAction(ButtonAction.MediaPlayPause) }
                )
            }
            item(key = "act_media_rewind") {
                DialogOptionCard(
                    title = "Rewind",
                    subtitle = "Send Rewind to the active media session",
                    selected = currentAction == ButtonAction.MediaRewind,
                    onClick = { onSelectAction(ButtonAction.MediaRewind) }
                )
            }
            item(key = "act_media_ff") {
                DialogOptionCard(
                    title = "Fast Forward",
                    subtitle = "Send Fast Forward to the active media session",
                    selected = currentAction == ButtonAction.MediaFastForward,
                    onClick = { onSelectAction(ButtonAction.MediaFastForward) }
                )
            }
            item(key = "act_media_prev") {
                DialogOptionCard(
                    title = "Previous",
                    subtitle = "Send Previous Track to the active media session",
                    selected = currentAction == ButtonAction.MediaPrevious,
                    onClick = { onSelectAction(ButtonAction.MediaPrevious) }
                )
            }
            item(key = "act_media_next") {
                DialogOptionCard(
                    title = "Next",
                    subtitle = "Send Next Track to the active media session",
                    selected = currentAction == ButtonAction.MediaNext,
                    onClick = { onSelectAction(ButtonAction.MediaNext) }
                )
            }
            item(key = "act_media_stop") {
                DialogOptionCard(
                    title = "Stop",
                    subtitle = "Send Stop to the active media session",
                    selected = currentAction == ButtonAction.MediaStop,
                    onClick = { onSelectAction(ButtonAction.MediaStop) }
                )
            }

            // Android
            item(key = "cat_android") {
                CategorySectionLabel("Android")
            }
            item(key = "act_android_settings") {
                DialogOptionCard(
                    title = "Open Android Settings",
                    subtitle = "Open the device's system Settings menu",
                    selected = currentAction == ButtonAction.OpenAndroidSettings,
                    onClick = { onSelectAction(ButtonAction.OpenAndroidSettings) }
                )
            }
            item(key = "act_display_settings") {
                DialogOptionCard(
                    title = "Open Display Settings",
                    subtitle = "Open Android Display & Sound settings",
                    selected = currentAction == ButtonAction.OpenDisplaySettings,
                    onClick = { onSelectAction(ButtonAction.OpenDisplaySettings) }
                )
            }
        }
    }
}

/**
 * Installed application selector dialog with app icon, app name, package name, search/filter field,
 * and refresh action.
 */
@Composable
internal fun InstalledAppSelectorDialog(
    installedApps: List<InstalledApp>,
    selectedPackageName: String?,
    onSelectApp: (InstalledApp) -> Unit,
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { app ->
                app.displayLabel.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q)
            }
        }
    }
    val listFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onRefreshApps()
        listFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Select Application",
        subtitle = "Choose an installed application to launch when this button is pressed.",
        width = 600.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            // Search & Refresh bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var searchFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(NuvioTheme.radii.md))
                        .background(NuvioTheme.colors.BackgroundCard)
                        .border(
                            width = if (searchFocused) 2.dp else NuvioTheme.spacing.hairline,
                            color = if (searchFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                        .onFocusChanged { searchFocused = it.isFocused }
                        .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search apps by name or package…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = NuvioTheme.colors.TextPrimary
                                ),
                                cursorBrush = SolidColor(NuvioTheme.colors.FocusRing),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Card(
                    onClick = onRefreshApps,
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        ),
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh installed applications",
                            tint = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Refresh",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }

            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = NuvioTheme.spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (installedApps.isEmpty()) {
                            "Loading installed applications…"
                        } else {
                            "No installed applications match \"$searchQuery\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { app: InstalledApp -> app.packageName }
                    ) { app ->
                        val isFirst = app.packageName == filteredApps.first().packageName
                        val isSelected = app.packageName == selectedPackageName
                        InstalledAppOptionRow(
                            app = app,
                            selected = isSelected,
                            modifier = if (isFirst) Modifier.focusRequester(listFocusRequester) else Modifier,
                            onClick = { onSelectApp(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppOptionRow(
    app: InstalledApp,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val iconBitmap = app.squareBitmap ?: app.displayIcon
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NuvioTheme.colors.Surface),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = app.displayLabel,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.FocusRing,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun ConfirmButtonMapperActionDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = message,
        width = 520.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            DialogOptionCard(
                title = confirmText,
                subtitle = "Confirm this operation",
                highlighted = true,
                onClick = onConfirm
            )
            DialogOptionCard(
                title = "Cancel",
                subtitle = "Keep current configuration",
                modifier = Modifier.focusRequester(cancelFocusRequester),
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun CategorySectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
internal fun DialogOptionCard(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(
                    NuvioTheme.spacing.hairline,
                    if (highlighted) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected || highlighted) {
                        NuvioTheme.colors.FocusRing
                    } else {
                        NuvioTheme.colors.TextPrimary
                    }
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            if (!trailing.isNullOrBlank()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.FocusRing,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

internal fun formatCategoryName(category: RemoteButtonCategory): String = when (category) {
    RemoteButtonCategory.NAVIGATION -> "Navigation"
    RemoteButtonCategory.MEDIA -> "Media Controls"
    RemoteButtonCategory.APP_SHORTCUT -> "App Shortcut Buttons"
    RemoteButtonCategory.TV_CONTROL -> "TV Controls"
    RemoteButtonCategory.COLORED -> "Colored Buttons"
    RemoteButtonCategory.OTHER -> "Other Buttons"
}
