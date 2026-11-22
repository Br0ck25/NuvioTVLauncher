@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import kotlin.math.roundToInt

/**
 * Read-only report of what this device and display can decode and output.
 *
 * Everything here is best-effort: MediaCodecList can advertise codecs the device cannot
 * actually play at speed, and an AVR's real decode mode cannot be read over HDMI. The
 * report is meant to explain what the hardware claims and to suggest settings, never to
 * silently change them or to block playback.
 */
object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"

    data class CodecRow(
        val label: String,
        val decoders: List<String>,
        val hardware: Boolean,
        val detail: String?
    ) {
        val supported: Boolean get() = decoders.isNotEmpty()
    }

    data class AudioOutput(
        val label: String,
        val typeName: String,
        val maxChannels: Int,
        val encodings: List<String>
    )

    data class FeatureRow(val label: String, val supported: Boolean)

    data class Report(
        val displaySize: String,
        val displayRefreshRate: String,
        val refreshRates: List<String>,
        val resolutions: List<String>,
        val displayModesKnown: Boolean,
        val frameRateSwitching: Boolean,
        val resolutionSwitching: Boolean,
        val hdrTypes: List<String>,
        val videoCodecs: List<CodecRow>,
        val audioCodecs: List<CodecRow>,
        val audioOutputs: List<AudioOutput>,
        val passthrough: List<FeatureRow>,
        val maxAudioChannels: Int
    ) {
        val supportedVideoLabels: List<String> get() = videoCodecs.filter { it.supported }.map { it.label }
        val missingVideoLabels: List<String> get() = videoCodecs.filterNot { it.supported }.map { it.label }
        val supportedAudioLabels: List<String> get() = audioCodecs.filter { it.supported }.map { it.label }
        val missingAudioLabels: List<String> get() = audioCodecs.filterNot { it.supported }.map { it.label }
        val passthroughLabels: List<String> get() = passthrough.filter { it.supported }.map { it.label }
        val hasHardwareVideo: Boolean get() = videoCodecs.any { it.supported && it.hardware }
        val supportsDolbyVisionDisplay: Boolean get() = hdrTypes.any { it.contains("Dolby Vision") }
        val supportsHdr10Display: Boolean
            get() = hdrTypes.any { it.contains("HDR10") || it.contains("HLG") }

        fun logSummary() {
            android.util.Log.i(
                TAG,
                "display=$displaySize@$displayRefreshRate rates=$refreshRates " +
                    "resolutions=$resolutions afr=$frameRateSwitching res=$resolutionSwitching " +
                    "hdr=$hdrTypes video=$supportedVideoLabels audio=$supportedAudioLabels " +
                    "passthrough=$passthroughLabels outputs=${audioOutputs.map { it.typeName }} " +
                    "channels=$maxAudioChannels"
            )
        }
    }

    private data class VideoMime(val label: String, val mime: String)

    private val videoMimes = listOf(
        VideoMime("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
        VideoMime("H.265 / HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC),
        VideoMime("AV1", MediaFormat.MIMETYPE_VIDEO_AV1),
        VideoMime("VP9", MediaFormat.MIMETYPE_VIDEO_VP9),
        VideoMime("MPEG-4", MediaFormat.MIMETYPE_VIDEO_MPEG4),
        VideoMime("VP8", MediaFormat.MIMETYPE_VIDEO_VP8)
    )

    private data class AudioMime(val label: String, val mime: String)

    private val audioMimes = listOf(
        AudioMime("AAC", MediaFormat.MIMETYPE_AUDIO_AAC),
        AudioMime("Dolby Digital (AC-3)", "audio/ac3"),
        AudioMime("Dolby Digital Plus (E-AC-3)", "audio/eac3"),
        AudioMime("Dolby TrueHD", "audio/true-hd"),
        AudioMime("DTS", "audio/vnd.dts"),
        AudioMime("DTS-HD", "audio/vnd.dts.hd"),
        AudioMime("FLAC", MediaFormat.MIMETYPE_AUDIO_FLAC),
        AudioMime("Opus", MediaFormat.MIMETYPE_AUDIO_OPUS),
        AudioMime("MP3", MediaFormat.MIMETYPE_AUDIO_MPEG)
    )

    /** Detects everything. Blocking (MediaCodecList can take a moment); call off the main thread. */
    fun detect(context: Context, activity: Activity?): Report {
        val display = if (activity != null) {
            runCatching { DisplayCapabilities.detect(activity) }
                .getOrDefault(DisplayCapabilities.Snapshot.Unknown)
        } else {
            DisplayCapabilities.Snapshot.Unknown
        }
        val refreshRates = display.supportedModes
            .map { formatRate(it.refreshRate) }
            .distinct()
            .sortedBy { it.toDoubleOrNull() ?: 0.0 }
        val resolutions = display.supportedModes
            .map { "${it.physicalWidth}×${it.physicalHeight}" }
            .distinct()
        val currentMode = display.supportedModes.firstOrNull { it.modeId == display.currentModeId }
        val displaySize = currentMode?.let { "${it.physicalWidth}×${it.physicalHeight}" }
            ?: runCatching {
                val dm = context.resources.displayMetrics
                "${dm.widthPixels}×${dm.heightPixels}"
            }.getOrDefault("Unknown")
        val displayRate = currentMode?.let { formatRate(it.refreshRate) } ?: "Unknown"

        val hdrTypes = hdrTypes(context, activity)

        val decoders = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }
            .getOrDefault(emptyArray())
            .filterNot { it.isEncoder }

        val video = videoMimes.map { entry ->
            val matching = decoders.filter { info -> supportsMime(info, entry.mime) }
            val hardware = matching.any { isHardware(it) }
            CodecRow(
                label = entry.label,
                decoders = matching.mapNotNull { it.name },
                hardware = hardware,
                detail = matching.firstNotNullOfOrNull { videoDetail(it, entry.mime) }
            )
        }

        val audio = audioMimes.map { entry ->
            val matching = decoders.filter { info -> supportsMime(info, entry.mime) }
            CodecRow(
                label = entry.label,
                decoders = matching.mapNotNull { it.name },
                hardware = matching.any { isHardware(it) },
                detail = null
            )
        }

        val outputs = audioOutputs(context)
        val passthrough = passthroughSupport(context)
        val maxChannels = outputs.map { it.maxChannels }.filter { it > 0 }.maxOrNull()
            ?: runCatching { AudioCapabilities.getCapabilities(context).maxChannelCount }
                .getOrDefault(2)

        return Report(
            displaySize = displaySize,
            displayRefreshRate = displayRate,
            refreshRates = refreshRates,
            resolutions = resolutions,
            displayModesKnown = display.apiSupported,
            frameRateSwitching = display.supportsFrameRateSwitching,
            resolutionSwitching = display.supportsResolutionSwitching,
            hdrTypes = hdrTypes,
            videoCodecs = video,
            audioCodecs = audio,
            audioOutputs = outputs,
            passthrough = passthrough,
            maxAudioChannels = maxChannels
        )
    }

    private fun supportsMime(info: MediaCodecInfo, mime: String): Boolean =
        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { info.isHardwareAccelerated }
            .getOrDefault(false)

    private fun videoDetail(info: MediaCodecInfo, mime: String): String? = runCatching {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities ?: return@runCatching null
        val size = when {
            video.isSizeSupported(3840, 2160) -> "up to 4K"
            video.isSizeSupported(1920, 1080) -> "up to 1080p"
            video.isSizeSupported(1280, 720) -> "up to 720p"
            else -> null
        }
        val tenBit = caps.profileLevels.any { profile ->
            when (mime) {
                MediaFormat.MIMETYPE_VIDEO_HEVC -> profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                MediaFormat.MIMETYPE_VIDEO_AV1 -> profile.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                    profile.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                    profile.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                else -> false
            }
        }
        listOfNotNull(size, if (tenBit) "10-bit" else null).joinToString(" · ").ifBlank { null }
    }.getOrNull()

    private fun hdrTypes(context: Context, activity: Activity?): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val types = runCatching {
            val display = activity?.window?.decorView?.display
                ?: context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        }.getOrDefault(emptyList())
        return types.mapNotNull { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> null
            }
        }.distinct()
    }

    private fun audioOutputs(context: Context): List<AudioOutput> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        return devices.mapNotNull { device ->
            val type = deviceTypeName(device.type) ?: return@mapNotNull null
            AudioOutput(
                label = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: type,
                typeName = type,
                maxChannels = runCatching { device.channelCounts.maxOrNull() ?: 0 }.getOrDefault(0),
                encodings = runCatching {
                    device.encodings.map { encodingName(it) }.filterNotNull().distinct()
                }.getOrDefault(emptyList())
            )
        }.distinctBy { it.typeName to it.label }
    }

    private fun passthroughSupport(context: Context): List<FeatureRow> = runCatching {
        val caps = AudioCapabilities.getCapabilities(context)
        listOf(
            "Dolby Digital (AC-3)" to C.ENCODING_AC3,
            "Dolby Digital Plus (E-AC-3)" to C.ENCODING_E_AC3,
            "Dolby Atmos (E-AC-3 JOC)" to C.ENCODING_E_AC3_JOC,
            "Dolby TrueHD" to C.ENCODING_DOLBY_TRUEHD,
            "DTS" to C.ENCODING_DTS,
            "DTS-HD" to C.ENCODING_DTS_HD,
            "Dolby AC-4" to C.ENCODING_AC4
        ).map { (label, encoding) -> FeatureRow(label, caps.supportsEncoding(encoding)) }
    }.getOrDefault(emptyList())

    private fun deviceTypeName(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line out"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line out"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Aux out"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TV speakers"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> null
    }

    private fun encodingName(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_AC3 -> "Dolby Digital"
        AudioFormat.ENCODING_E_AC3 -> "Dolby Digital Plus"
        AudioFormat.ENCODING_E_AC3_JOC -> "Dolby Atmos"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_AC4 -> "Dolby AC-4"
        AudioFormat.ENCODING_IEC61937 -> "IEC 61937"
        else -> null
    }

    private fun formatRate(rate: Float): String {
        val rounded = (rate * 100f).roundToInt() / 100f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else "%.2f".format(rounded)
    }
}
