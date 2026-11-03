package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences

/**
 * "Live TV" rows: show Live TV (IPTV channel lists) in the menu. Off by default. Under it, while
 * Live TV is on, "Channel previews" and their sound (both on by default).
 */
@Composable
internal fun LiveTvSettingsItems() {
    val context = LocalContext.current
    LiveTvPreferences.ensureLoaded(context)
    val checked by LiveTvPreferences.enabled.collectAsStateWithLifecycle()
    val previews by LiveTvPreferences.previews.collectAsStateWithLifecycle()
    val previewSound by LiveTvPreferences.previewSound.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.settings_live_tv_title),
        subtitle = stringResource(R.string.settings_live_tv_description),
        checked = checked,
        onToggle = { LiveTvPreferences.setEnabled(context, !checked) },
    )
    if (checked) {
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
