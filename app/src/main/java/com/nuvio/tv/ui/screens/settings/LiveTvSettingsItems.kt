package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.live_tv_title),
            subtitle = stringResource(R.string.settings_live_tv_description)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            LiveTvSettingsItems(initialFocusRequester = initialFocusRequester)
        }
    }
}

/**
 * "Live TV" rows: show Live TV (IPTV channel lists) in the menu. Off by default. Under it, while
 * Live TV is on, "Channel previews" and their sound (both on by default).
 */
@Composable
internal fun LiveTvSettingsItems(
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    LiveTvPreferences.ensureLoaded(context)
    val checked by LiveTvPreferences.enabled.collectAsStateWithLifecycle()
    val previews by LiveTvPreferences.previews.collectAsStateWithLifecycle()
    val previewSound by LiveTvPreferences.previewSound.collectAsStateWithLifecycle()
    val torboxDownloads by LiveTvPreferences.torboxDownloads.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.settings_live_tv_title),
        subtitle = stringResource(R.string.settings_live_tv_description),
        checked = checked,
        onToggle = { LiveTvPreferences.setEnabled(context, !checked) },
        modifier = if (initialFocusRequester != null) {
            Modifier.focusRequester(initialFocusRequester)
        } else {
            Modifier
        }
    )
    if (checked) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_live_tv_torbox_title),
            subtitle = stringResource(R.string.settings_live_tv_torbox_description),
            checked = torboxDownloads,
            onToggle = { LiveTvPreferences.setTorboxDownloads(context, !torboxDownloads) },
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_live_tv_previews_title),
            subtitle = stringResource(R.string.settings_live_tv_previews_description),
            checked = previews,
            onToggle = { LiveTvPreferences.setPreviews(context, !previews) },
        )
        if (previews) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_live_tv_preview_sound_title),
                subtitle = stringResource(R.string.settings_live_tv_preview_sound_description),
                checked = previewSound,
                onToggle = { LiveTvPreferences.setPreviewSound(context, !previewSound) },
            )
        }
    }
}
