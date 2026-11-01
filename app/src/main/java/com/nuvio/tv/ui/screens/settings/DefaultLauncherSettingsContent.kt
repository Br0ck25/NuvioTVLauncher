package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
                    if (setup.isAccessibilityEnabled) R.string.apps_home_override_active
                    else R.string.apps_home_override
                ),
                subtitle = stringResource(R.string.settings_home_override_subtitle),
                onClick = setup.openAccessibilitySettings
            )
        }
    }
}
