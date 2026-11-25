package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DownmixLfeLevel
import com.nuvio.tv.data.local.NightMode
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.core.audio.EqualizerOwner
import com.nuvio.tv.core.audio.SevenBandEqualizerSettings
import com.nuvio.tv.core.audio.SevenBandPreset
import com.nuvio.tv.ui.components.SevenBandEqualizerFaders
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@Composable
internal fun PlaybackAudioSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val enabled = settings.playerPreference != PlayerPreference.EXTERNAL
    val isExoEngine = settings.usesExoPlayerEngine

    SettingsNote(text = stringResource(R.string.audio_passthrough_info))

    SettingsActionRow(
        title = stringResource(R.string.audio_preferred_lang),
        subtitle = null,
        value = audioLanguageLabel(settings.preferredAudioLanguage),
        enabled = enabled,
        onClick = { onOpenDialog(PlaybackDialog.AUDIO_LANGUAGE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.sub_secondary_lang),
        subtitle = null,
        value = secondaryAudioLanguageLabel(settings.secondaryPreferredAudioLanguage),
        enabled = enabled,
        onClick = { onOpenDialog(PlaybackDialog.SECONDARY_AUDIO_LANGUAGE) }
    )

    if (isExoEngine) {
        SettingsToggleRow(
            title = stringResource(R.string.audio_skip_silence),
            subtitle = stringResource(R.string.audio_skip_silence_sub),
            checked = settings.skipSilence,
            onToggle = { onUpdate { setSkipSilence(!settings.skipSilence) } },
            enabled = enabled
        )
        SettingsActionRow(
            title = stringResource(R.string.audio_night_mode),
            subtitle = stringResource(R.string.audio_night_mode_sub),
            value = nightModeLabel(settings.nightMode),
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.NIGHT_MODE) }
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.audio_remember_delay_per_device),
        subtitle = stringResource(R.string.audio_remember_delay_per_device_sub),
        checked = settings.rememberAudioDelayPerDevice,
        onToggle = { onUpdate { setRememberAudioDelayPerDevice(!settings.rememberAudioDelayPerDevice) } },
        enabled = enabled
    )

    if (!enabled) return

    SevenBandEqualizerRows()

    if (!isExoEngine) return

    SettingsSectionLabel(text = stringResource(R.string.audio_advanced_section))
    SettingsNote(text = stringResource(R.string.audio_advanced_warning), tone = SettingsNoteTone.Warning)

    SettingsActionRow(
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = null,
        value = decoderPriorityLabel(settings.decoderPriority),
        enabled = enabled,
        onClick = { onOpenDialog(PlaybackDialog.DECODER_PRIORITY) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.audio_enable_downmix_title),
        subtitle = stringResource(R.string.audio_enable_downmix_subtitle),
        checked = settings.effectiveDownmixEnabled,
        onToggle = { onUpdate { setDownmixEnabled(!settings.effectiveDownmixEnabled) } },
        enabled = enabled && settings.isPreferAppDecoder
    )
    if (settings.effectiveDownmixEnabled) {
        SettingsActionRow(
            title = stringResource(R.string.audio_number_of_channels),
            subtitle = null,
            value = settings.audioOutputChannels.displayLabel,
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.AUDIO_OUTPUT_CHANNELS) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_maintain_original_audio_on_downmix_title),
            subtitle = stringResource(R.string.audio_maintain_original_audio_on_downmix_subtitle),
            checked = settings.maintainOriginalAudioOnDownmix,
            onToggle = { onUpdate { setMaintainOriginalAudioOnDownmix(!settings.maintainOriginalAudioOnDownmix) } },
            enabled = enabled
        )
        SettingsActionRow(
            title = stringResource(R.string.audio_downmix_lfe_level),
            subtitle = stringResource(R.string.audio_downmix_lfe_level_sub),
            value = downmixLfeLevelLabel(settings.downmixLfeLevel),
            enabled = enabled && !settings.audioOutputChannels.hasLfe,
            onClick = { onOpenDialog(PlaybackDialog.DOWNMIX_LFE_LEVEL) }
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.audio_tunneled),
        subtitle = stringResource(R.string.audio_tunneled_sub),
        checked = settings.effectiveTunnelingEnabled,
        onToggle = { onUpdate { setTunnelingEnabled(!settings.effectiveTunnelingEnabled) } },
        enabled = enabled && settings.isTunnelingCompatible
    )
    SettingsToggleRow(
        title = stringResource(R.string.audio_force_optical_passthrough),
        subtitle = stringResource(R.string.audio_force_optical_passthrough_sub),
        checked = settings.forceOpticalPassthrough && settings.decoderPriority != 0,
        onToggle = { onUpdate { setForceOpticalPassthrough(!settings.forceOpticalPassthrough) } },
        enabled = enabled && settings.decoderPriority != 0
    )
}

@Composable
private fun SevenBandEqualizerRows() {
    val context = LocalContext.current
    LaunchedEffect(context) { SevenBandEqualizerSettings.init(context) }
    val profile by SevenBandEqualizerSettings.profile(EqualizerOwner.PLAYBACK).collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }

    SettingsActionRow(
        title = "Seven-band equalizer",
        subtitle = "Choose a preset and adjust the seven frequency bands.",
        value = profile.preset.label,
        onClick = { showEditor = true }
    )

    if (showEditor) {
        val presetFocusRequester = remember { FocusRequester() }
        val configuration = LocalConfiguration.current
        val dialogWidth = (configuration.screenWidthDp.dp - 64.dp).coerceAtLeast(320.dp).coerceAtMost(1000.dp)
        val faderTrackHeight = when {
            configuration.screenHeightDp < 560 -> 112.dp
            configuration.screenHeightDp < 720 -> 140.dp
            else -> 174.dp
        }
        LaunchedEffect(showEditor) {
            if (showEditor) {
                delay(80)
                runCatching { presetFocusRequester.requestFocus() }
            }
        }
        NuvioDialog(
            onDismiss = { showEditor = false },
            title = "Seven-band equalizer",
            subtitle = "Applies to decoded audio in built-in ExoPlayer, MPV, and VLC. Enabling it uses PCM audio, so bitstream passthrough is unavailable.",
            width = dialogWidth,
            scrollContent = true
        ) {
            SettingsActionRow(
                title = "Preset",
                subtitle = "Choose a starting curve, then tune individual bands.",
                value = profile.preset.label,
                modifier = Modifier.focusRequester(presetFocusRequester),
                onClick = { showPresetPicker = true }
            )
            if (profile.preset != SevenBandPreset.OFF) {
                SevenBandEqualizerFaders(
                    gainsDb = profile.gainsDb,
                    onBandChange = { index, gain ->
                        SevenBandEqualizerSettings.setBand(EqualizerOwner.PLAYBACK, index, gain)
                    },
                    accent = NuvioTheme.colors.Secondary,
                    text = NuvioTheme.colors.TextPrimary,
                    muted = NuvioTheme.colors.TextSecondary,
                    trackHeight = faderTrackHeight,
                    onMenuPressed = { runCatching { presetFocusRequester.requestFocus() } }
                )
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_close),
                    onClick = { showEditor = false },
                    primary = true
                )
            }
        }
    }

    if (showPresetPicker) {
        SettingsSingleChoiceDialog(
            title = "Equalizer preset",
            options = SevenBandPreset.selectable.map { SettingsPickerOption(it, it.label) },
            selectedValue = profile.preset.takeIf { it != SevenBandPreset.CUSTOM } ?: SevenBandPreset.FLAT,
            onOptionSelected = {
                SevenBandEqualizerSettings.selectPreset(EqualizerOwner.PLAYBACK, it)
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false },
            width = 420.dp,
            maxHeight = 480.dp
        )
    }
}

@Composable
internal fun audioLanguageLabel(code: String): String = when (code) {
    AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
    AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
    AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_lang_original)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
}

@Composable
private fun secondaryAudioLanguageLabel(code: String?): String = when {
    code == null -> stringResource(R.string.sub_not_set)
    code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) -> stringResource(R.string.audio_lang_original)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
}

@Composable
internal fun decoderPriorityLabel(priority: Int): String = when (priority) {
    0 -> stringResource(R.string.audio_decoder_device_only)
    2 -> stringResource(R.string.audio_decoder_prefer_app)
    else -> stringResource(R.string.audio_decoder_prefer_device)
}

@Composable
private fun downmixLfeLevelLabel(level: DownmixLfeLevel): String = when (level) {
    DownmixLfeLevel.OFF -> stringResource(R.string.audio_downmix_lfe_level_off)
    DownmixLfeLevel.HALF -> stringResource(R.string.audio_downmix_lfe_level_half)
    DownmixLfeLevel.FULL -> stringResource(R.string.audio_downmix_lfe_level_full)
}

@Composable
private fun nightModeLabel(mode: NightMode): String = when (mode) {
    NightMode.OFF -> stringResource(R.string.audio_night_mode_off)
    NightMode.LIGHT -> stringResource(R.string.audio_night_mode_light)
    NightMode.MEDIUM -> stringResource(R.string.audio_night_mode_medium)
    NightMode.STRONG -> stringResource(R.string.audio_night_mode_strong)
}

@Composable
internal fun AudioSettingsDialogs(
    dialog: PlaybackDialog?,
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        PlaybackDialog.AUDIO_LANGUAGE -> AudioLanguageSelectionDialog(
            selectedLanguage = settings.preferredAudioLanguage,
            onLanguageSelected = { language ->
                onUpdate { setPreferredAudioLanguage(language) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.SECONDARY_AUDIO_LANGUAGE -> LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = settings.secondaryPreferredAudioLanguage,
            showNoneOption = true,
            extraOptions = listOf(
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = { language ->
                onUpdate { setSecondaryPreferredAudioLanguage(language) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.AUDIO_OUTPUT_CHANNELS -> AudioOutputChannelsDialog(
            selectedChannels = settings.audioOutputChannels,
            onChannelsSelected = { channels ->
                onUpdate { setAudioOutputChannels(channels) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.DOWNMIX_LFE_LEVEL -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.audio_downmix_lfe_level),
            subtitle = stringResource(R.string.audio_downmix_lfe_level_desc),
            options = DownmixLfeLevel.entries.map { SettingsPickerOption(it, downmixLfeLevelLabel(it)) },
            selectedValue = settings.downmixLfeLevel,
            onOptionSelected = { level ->
                onUpdate { setDownmixLfeLevel(level) }
                onDismiss()
            },
            onDismiss = onDismiss,
            width = 420.dp,
            maxHeight = 320.dp
        )
        PlaybackDialog.NIGHT_MODE -> NightModeDialog(
            selectedMode = settings.nightMode,
            onModeSelected = { mode ->
                onUpdate { setNightMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.DECODER_PRIORITY -> DecoderPriorityDialog(
            selectedPriority = settings.decoderPriority,
            onPrioritySelected = { priority ->
                onUpdate { setDecoderPriority(priority) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}

@Composable
private fun AudioOutputChannelsDialog(
    selectedChannels: AudioOutputChannels,
    onChannelsSelected: (AudioOutputChannels) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_number_of_channels),
        subtitle = stringResource(R.string.audio_number_of_channels_desc),
        options = AudioOutputChannels.entries.map { SettingsPickerOption(it, it.displayLabel) },
        selectedValue = selectedChannels,
        onOptionSelected = onChannelsSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 420.dp
    )
}

@Composable
private fun NightModeDialog(
    selectedMode: NightMode,
    onModeSelected: (NightMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(NightMode.OFF, stringResource(R.string.audio_night_mode_off)),
        SettingsPickerOption(
            NightMode.LIGHT,
            stringResource(R.string.audio_night_mode_light),
            stringResource(R.string.audio_night_mode_light_desc)
        ),
        SettingsPickerOption(
            NightMode.MEDIUM,
            stringResource(R.string.audio_night_mode_medium),
            stringResource(R.string.audio_night_mode_medium_desc)
        ),
        SettingsPickerOption(
            NightMode.STRONG,
            stringResource(R.string.audio_night_mode_strong),
            stringResource(R.string.audio_night_mode_strong_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_night_mode),
        subtitle = stringResource(R.string.audio_night_mode_desc),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun AudioLanguageSelectionDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val specialOptions = listOf(
        AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_default),
        AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
        AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
    )
    val originalHint = stringResource(R.string.audio_lang_original_hint)
    val allOptions = specialOptions.map { (code, name) ->
        SettingsPickerOption(
            value = code,
            title = name,
            description = if (code == AudioLanguageOption.ORIGINAL) originalHint else null
        )
    } + AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }.map {
        SettingsPickerOption(
            value = it.code,
            title = it.displayName,
            trailing = it.code.uppercase()
        )
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_preferred_lang),
        options = allOptions,
        selectedValue = selectedLanguage,
        onOptionSelected = onLanguageSelected,
        onDismiss = onDismiss,
        width = 400.dp,
        maxHeight = 320.dp
    )
}

@Composable
internal fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(0, stringResource(R.string.audio_decoder_device_only), stringResource(R.string.audio_decoder_device_only_desc)),
        SettingsPickerOption(1, stringResource(R.string.audio_decoder_prefer_device), stringResource(R.string.audio_decoder_prefer_device_desc)),
        SettingsPickerOption(2, stringResource(R.string.audio_decoder_prefer_app), stringResource(R.string.audio_decoder_prefer_app_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = stringResource(R.string.audio_decoder_controls),
        options = options,
        selectedValue = selectedPriority,
        onOptionSelected = onPrioritySelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}
