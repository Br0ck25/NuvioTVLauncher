package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.setup.LocalAdbSetupFactory
import com.nuvio.tv.ui.components.rememberHomeSetupState
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Three ways to make Nuvio the Home app, in the order they should be tried: Android's own Home-app
 * dialog, the system Home settings, and the on-device Wireless debugging setup for firmware where
 * neither of the first two is offered.
 */
@Composable
internal fun DefaultLauncherSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val setup = rememberHomeSetupState()
    val context = LocalContext.current
    // Null on flavors and Android versions without the on-device ADB setup.
    val selfSetup = remember { LocalAdbSetupFactory.create(context) }
    var showSelfSetup by remember { mutableStateOf(false) }
    var setupRevision by remember { mutableStateOf(0) }

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
                    if (setup.roleRequestDenied) R.string.settings_home_role_not_selected
                    else R.string.settings_make_home_app_subtitle
                ),
                onClick = setup.requestDefaultHome,
                enabled = !setup.isDefaultHome,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_open_home_settings),
                subtitle = stringResource(
                    if (setup.homeSettingsUnavailable) R.string.settings_home_settings_unavailable
                    else R.string.settings_open_home_settings_subtitle
                ),
                onClick = setup.openHomeSettings
            )
            // Hidden on the Play Store flavor, which ships no on-device setup. On an older Android
            // it stays visible but explains why it cannot run, rather than disappearing silently.
            if (selfSetup != null || LocalAdbSetupFactory.available(context)) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_self_setup_title),
                    subtitle = stringResource(
                        when {
                            selfSetup == null -> R.string.settings_self_setup_unavailable_subtitle
                            setup.isDefaultHome -> R.string.settings_self_setup_done_subtitle
                            setupRevision > 0 -> R.string.settings_self_setup_retry_subtitle
                            else -> R.string.settings_self_setup_subtitle
                        }
                    ),
                    onClick = { showSelfSetup = true },
                    enabled = selfSetup != null
                )
            }
        }
        if (showSelfSetup && selfSetup != null) {
            LocalAdbSetupDialog(
                setup = selfSetup,
                onDismiss = {
                    showSelfSetup = false
                    setupRevision++
                    setup.refresh()
                },
                onFinished = {
                    showSelfSetup = false
                    setupRevision++
                    setup.refresh()
                }
            )
        }
    }
}
