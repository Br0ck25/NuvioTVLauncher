package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.core.player.DeviceCapabilities
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What can this box really play?" — a read-only probe of the display, hardware decoders
 * and audio outputs, with suggestions the user can apply one at a time.
 */
@Composable
internal fun PlaybackCapabilitySection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var generation by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<DeviceCapabilities.Report?>(null) }
    var checking by remember { mutableStateOf(true) }

    LaunchedEffect(activity, generation) {
        checking = true
        report = withContext(Dispatchers.IO) {
            runCatching { DeviceCapabilities.detect(context, activity) }.getOrNull()
        }
        checking = false
        report?.logSummary()
    }

    val current = report
    SettingsSectionLabel(text = "This device")
    SettingsNote(
        text = "Checked on this TV. The hardware's claims are a guide, not a guarantee: " +
            "a real file is the only way to prove a format plays, especially Dolby Vision and DTS."
    )

    if (checking || current == null) {
        SettingsActionRow(
            title = "Checking what this device supports…",
            subtitle = null,
            value = null,
            enabled = false,
            onClick = {}
        )
    } else {
        SettingsActionRow(
            title = "Display",
            subtitle = buildString {
                append(
                    if (current.displayModesKnown) {
                        "Refresh-rate switching: ${yesNo(current.frameRateSwitching)} · " +
                            "resolution switching: ${yesNo(current.resolutionSwitching)}"
                    } else {
                        "This box does not report its display modes."
                    }
                )
                if (current.refreshRates.size > 1) {
                    append(" · Rates: ")
                    append(current.refreshRates.joinToString(", ") { "$it Hz" })
                }
                if (current.resolutions.size > 1) {
                    append(" · Resolutions: ")
                    append(current.resolutions.joinToString(", "))
                }
            },
            value = "${current.displaySize} · ${current.displayRefreshRate} Hz",
            onClick = { generation++ }
        )
        SettingsActionRow(
            title = "HDR",
            subtitle = if (current.hdrTypes.isEmpty()) {
                "No HDR types reported by the display. HDR titles will be tone-mapped to SDR."
            } else {
                "Reported by the TV over HDMI. Dolby Vision needs both the TV and the player to support it."
            },
            value = current.hdrTypes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "SDR only",
            onClick = { generation++ }
        )
        SettingsActionRow(
            title = "Video decoding",
            subtitle = codecDetail(current.supportedVideoLabels, current.missingVideoLabels),
            value = current.supportedVideoLabels.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None reported",
            onClick = { generation++ }
        )
        SettingsActionRow(
            title = "Audio decoding",
            subtitle = codecDetail(current.supportedAudioLabels, current.missingAudioLabels) +
                " The app's built-in FFmpeg decoder also handles AC-3, E-AC-3, DTS, DTS-HD and " +
                "TrueHD when the device has no platform decoder.",
            value = current.supportedAudioLabels.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None reported",
            onClick = { generation++ }
        )
        SettingsActionRow(
            title = "Audio passthrough",
            subtitle = "Bitstream formats the current output reports it can carry to a receiver.",
            value = current.passthroughLabels.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: "None reported",
            onClick = { generation++ }
        )
        SettingsActionRow(
            title = "Audio output",
            subtitle = outputDetail(current),
            value = current.audioOutputs.firstOrNull()?.typeName ?: "No output reported",
            onClick = { generation++ }
        )
        SettingsNote(text = "Tap any row to check again after changing a TV or receiver setting.")

        val suggestions = capabilitySuggestions(current, settings)
        SettingsSectionLabel(text = "Suggested settings")
        if (suggestions.isEmpty()) {
            SettingsNote(text = "No changes suggested. The playback settings already match this device.")
        } else {
            SettingsNote(
                text = "Suggestions only — nothing is changed until you pick one. Each one opens here, " +
                    "so you can undo it in the section above."
            )
            suggestions.forEach { suggestion ->
                SettingsActionRow(
                    title = suggestion.title,
                    subtitle = suggestion.detail,
                    value = suggestion.actionLabel,
                    onClick = { onUpdate(suggestion.apply) }
                )
            }
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun codecDetail(supported: List<String>, missing: List<String>): String = when {
    missing.isEmpty() -> "Every format checked has a decoder on this device."
    supported.isEmpty() -> "No decoder found for any of: ${missing.joinToString(", ")}."
    else -> "No decoder for: ${missing.joinToString(", ")}."
}

private fun outputDetail(report: DeviceCapabilities.Report): String {
    if (report.audioOutputs.isEmpty()) return "No output device reported."
    val extra = report.audioOutputs.drop(1).joinToString(", ") { output ->
        if (output.maxChannels > 0) "${output.typeName} · ${output.maxChannels} ch" else output.typeName
    }
    val encodings = report.audioOutputs.first().encodings.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
    return buildString {
        append("Highest channel count: ${report.maxAudioChannels}.")
        if (encodings != null) append(" Encodings: $encodings.")
        if (extra.isNotBlank()) append(" Also: $extra.")
    }
}

private data class CapabilitySuggestion(
    val key: String,
    val title: String,
    val detail: String,
    val actionLabel: String = "Apply",
    val apply: suspend PlaybackSettingsViewModel.() -> Unit
)

private fun capabilitySuggestions(
    report: DeviceCapabilities.Report,
    settings: PlayerSettings
): List<CapabilitySuggestion> {
    val suggestions = mutableListOf<CapabilitySuggestion>()
    val afrOn = settings.frameRateMatchingMode != FrameRateMatchingMode.OFF

    if (report.displayModesKnown && report.frameRateSwitching && !afrOn) {
        suggestions += CapabilitySuggestion(
            key = "afr_off",
            title = "Match the display refresh rate to the video",
            detail = "This display reports more than one refresh rate. Matching it reduces judder on " +
                "24 fps films and keeps the TV from doing its own conversion.",
            actionLabel = "Turn on",
            apply = { setFrameRateMatchingMode(FrameRateMatchingMode.START_STOP) }
        )
    }
    if (report.displayModesKnown && !report.frameRateSwitching && afrOn) {
        suggestions += CapabilitySuggestion(
            key = "afr_unsupported",
            title = "Turn off refresh-rate matching",
            detail = "This display reports a single refresh rate, so matching cannot change anything " +
                "and only adds a mode switch when playback starts.",
            actionLabel = "Turn off",
            apply = { setFrameRateMatchingMode(FrameRateMatchingMode.OFF) }
        )
    }
    if (report.displayModesKnown && report.resolutionSwitching && !settings.resolutionMatchingEnabled) {
        suggestions += CapabilitySuggestion(
            key = "resolution_off",
            title = "Match the display resolution to the video",
            detail = "This display reports more than one resolution. Matching avoids the TV scaling " +
                "1080p content up to 4K and back.",
            actionLabel = "Turn on",
            apply = { setResolutionMatchingEnabled(true) }
        )
    }
    if (report.displayModesKnown && !report.resolutionSwitching && settings.resolutionMatchingEnabled) {
        suggestions += CapabilitySuggestion(
            key = "resolution_unsupported",
            title = "Turn off resolution matching",
            detail = "This display reports a single resolution, so matching cannot change anything.",
            actionLabel = "Turn off",
            apply = { setResolutionMatchingEnabled(false) }
        )
    }
    val hardwareHevcOrAv1 = report.videoCodecs.any {
        it.supported && it.hardware && (it.label == "H.265 / HEVC" || it.label == "AV1")
    }
    if (hardwareHevcOrAv1 && settings.decoderPriority == 2) {
        suggestions += CapabilitySuggestion(
            key = "hardware_decoder",
            title = "Prefer the device decoder",
            detail = "Hardware HEVC/AV1 decoding is available. The app decoder uses more CPU, can lose " +
                "HDR output and may not keep up with 4K.",
            actionLabel = "Prefer device",
            apply = { setDecoderPriority(1) }
        )
    }
    if (settings.downmixEnabled &&
        settings.audioOutputChannels.channelCount > 2 &&
        report.audioOutputs.isNotEmpty() &&
        report.maxAudioChannels in 1..2
    ) {
        suggestions += CapabilitySuggestion(
            key = "downmix_stereo",
            title = "Downmix to 2.0 for this output",
            detail = "The output reports at most ${report.maxAudioChannels} channels. A 7.1 downmix is " +
                "folded by the TV anyway; setting 2.0 keeps the mix predictable.",
            actionLabel = "Set 2.0",
            apply = {
                setDownmixEnabled(true)
                setAudioOutputChannels(AudioOutputChannels.CHANNELS_2_0)
            }
        )
    }
    return suggestions
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
