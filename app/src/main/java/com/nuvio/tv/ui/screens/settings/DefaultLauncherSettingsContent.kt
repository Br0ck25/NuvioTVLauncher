package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.core.launcher.AccessibilityWatchdog
import com.nuvio.tv.core.launcher.LocalAdbSetupFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.rememberLauncherSetupState
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun DefaultLauncherSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val setup = rememberLauncherSetupState()
    val context = LocalContext.current
    // Null on flavors/Android versions without the on-device ADB setup.
    val selfSetup = remember { LocalAdbSetupFactory.create(context) }
    var showSelfSetup by remember { mutableStateOf(false) }
    var setupRevision by remember { mutableStateOf(0) }
    var capturingAppsKey by remember { mutableStateOf(false) }
    val appsRowPosition by com.nuvio.tv.core.apps.AppsRowSettings.position(context).collectAsState()
    val appsKeyChosen = remember(capturingAppsKey) { com.nuvio.tv.core.launcher.AppsKeyStore.chosen(context) }
    val secureGranted = remember(setupRevision) { AccessibilityWatchdog.canWriteSecureSettings(context) }
    var keepOverride by remember(setupRevision) { mutableStateOf(AccessibilityWatchdog.isOptedIn(context)) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_default_launcher),
            subtitle = stringResource(R.string.settings_default_launcher_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(
                    if (setup.isDefaultHome) R.string.apps_default_launcher_active
                    else R.string.settings_make_home_app
                ),
                subtitle = stringResource(
                    if (setup.showFallback) R.string.settings_home_role_not_selected
                    else R.string.settings_make_home_app_subtitle
                ),
                onClick = setup.requestDefaultHome,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_open_home_settings),
                subtitle = stringResource(
                    if (setup.settingsUnavailable) R.string.settings_home_settings_unavailable
                    else R.string.settings_open_home_settings_subtitle
                ),
                onClick = setup.openHomeSettings
            )
            SettingsActionRow(
                title = stringResource(
                    if (setup.isAccessibilityActive) R.string.apps_home_override_active
                    else if (setup.isAccessibilityEnabled) R.string.apps_home_override_pending
                    else R.string.apps_home_override
                ),
                subtitle = stringResource(R.string.settings_home_override_subtitle),
                onClick = setup.openAccessibilitySettings
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_apps_button_title),
                subtitle = stringResource(R.string.settings_apps_button_subtitle),
                value = if (appsKeyChosen > 0) com.nuvio.tv.core.launcher.AppsKeyStore.describe(appsKeyChosen) else stringResource(R.string.settings_apps_button_default),
                onClick = { capturingAppsKey = true }
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_apps_row_title),
                subtitle = stringResource(R.string.settings_apps_row_subtitle),
                value = stringResource(
                    when (appsRowPosition) {
                        com.nuvio.tv.core.apps.AppsRowPosition.TOP -> R.string.apps_row_top
                        com.nuvio.tv.core.apps.AppsRowPosition.UNDER_CONTINUE_WATCHING -> R.string.apps_row_under_cw
                        com.nuvio.tv.core.apps.AppsRowPosition.BOTTOM -> R.string.apps_row_bottom
                    }
                ),
                onClick = { com.nuvio.tv.core.apps.AppsRowSettings.set(context, appsRowPosition.next()) }
            )
            if (selfSetup == null) {
                val support = com.nuvio.tv.core.launcher.PhoneSetupSupport.current()
                SettingsNote(
                    text = if (support == com.nuvio.tv.core.launcher.PhoneSetupSupport.ANDROID_TOO_OLD) {
                        stringResource(R.string.self_setup_unavailable_old, android.os.Build.VERSION.RELEASE)
                    } else {
                        stringResource(R.string.self_setup_unavailable_build)
                    },
                    tone = SettingsNoteTone.Warning
                )
            }
            if (selfSetup != null) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_self_setup_title),
                    subtitle = stringResource(
                        if (secureGranted) R.string.settings_self_setup_done_subtitle
                        else R.string.settings_self_setup_subtitle
                    ),
                    onClick = { showSelfSetup = true }
                )
            }
            if (secureGranted) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_keep_override_title),
                    subtitle = stringResource(R.string.settings_keep_override_subtitle),
                    checked = keepOverride,
                    onToggle = {
                        keepOverride = !keepOverride
                        AccessibilityWatchdog.setOptIn(context, keepOverride)
                    }
                )
            }
        }
        if (capturingAppsKey) AppsKeyCaptureDialog(onDone = { capturingAppsKey = false })
        if (showSelfSetup && selfSetup != null) {
            LocalAdbSetupDialog(
                setup = selfSetup,
                onDismiss = {
                    showSelfSetup = false
                    setupRevision++
                },
                onFinished = {
                    showSelfSetup = false
                    setupRevision++
                }
            )
        }
    }
}
