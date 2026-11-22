package com.nuvio.tv.youtube

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes
import java.util.Locale

private const val GB = 1024L * 1024L * 1024L
private const val ONE_GB = GB
private const val ONE_AND_HALF_GB = 3L * GB / 2L

/**
 * What this device can decode reliably, used to decide YouTube's starting codec and quality.
 *
 * This is deliberately capability-based rather than brand-based: a 1.5 GB box with a good
 * hardware H.264 decoder can still be a fine 1080p player, while a 2 GB box with a poor VP9
 * decoder can freeze after seeking.
 */
internal data class YouTubeDeviceProfile(
    val lowRamDevice: Boolean,
    val totalRamBytes: Long,
    val hasHardwareAvc: Boolean,
    val hasHardwareVp9: Boolean,
    val hasHardwareAv1: Boolean,
    val maxAvcHeight: Int?,
    val maxVp9Height: Int?,
    val maxAv1Height: Int?,
) {
    /**
     * H.264 + a quality cap are worth using when the device is short on memory or has no
     * hardware VP9 decoder. AV1 alone doesn't trigger this: the normal codec order already
     * leaves AV1 last.
     */
    val needsCompatibility: Boolean
        get() =
            lowRamDevice ||
                (totalRamBytes in 1L..ONE_AND_HALF_GB) ||
                !hasHardwareAvc ||
                !hasHardwareVp9 ||
                (maxVp9Height != null && maxVp9Height < 1080)

    /** The starting size used when compatibility is active. */
    val compatibilityHeight: Int
        get() = when {
            !hasHardwareAvc -> 720
            totalRamBytes in 1L..ONE_GB -> 720
            else -> 1080
        }

    /** A short explanation for the settings screen and diagnostics. */
    val reason: String
        get() = when {
            !hasHardwareAvc -> "Limited hardware decoding"
            !hasHardwareVp9 -> "No hardware VP9 decoder"
            maxVp9Height != null && maxVp9Height < 1080 -> "VP9 limited to ${maxVp9Height}p"
            lowRamDevice || totalRamBytes in 1L..ONE_AND_HALF_GB -> "Low memory"
            else -> "Full hardware decoding"
        }
}

internal object YouTubeCompatibility {
    @Volatile
    private var cached: YouTubeDeviceProfile? = null

    fun deviceProfile(context: Context): YouTubeDeviceProfile {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo()
            runCatching { activityManager?.getMemoryInfo(memory) }
            val avc = hardwareSupport(MimeTypes.VIDEO_H264)
            val vp9 = hardwareSupport(MimeTypes.VIDEO_VP9)
            val av1 = hardwareSupport(MimeTypes.VIDEO_AV1)
            val profile = YouTubeDeviceProfile(
                lowRamDevice = activityManager?.isLowRamDevice == true,
                totalRamBytes = memory.totalMem,
                hasHardwareAvc = avc.available,
                hasHardwareVp9 = vp9.available,
                hasHardwareAv1 = av1.available,
                maxAvcHeight = avc.maxHeight,
                maxVp9Height = vp9.maxHeight,
                maxAv1Height = av1.maxHeight,
            )
            cached = profile
            profile
        }
    }

    private data class DecoderSupport(val available: Boolean, val maxHeight: Int?)

    private fun hardwareSupport(mimeType: String): DecoderSupport {
        var available = false
        var maxHeight: Int? = null
        runCatching {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for (info in codecs) {
                if (info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mimeType, true) }) continue
                if (!isHardwareDecoder(info)) continue
                available = true
                val height = runCatching {
                    info.getCapabilitiesForType(mimeType).videoCapabilities?.supportedHeights?.upper
                }.getOrNull()
                if (height != null && height > 0) {
                    maxHeight = maxOf(maxHeight ?: 0, height)
                }
            }
        }
        return DecoderSupport(available, maxHeight)
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated) return true
        return !isSoftwareDecoderName(info.name)
    }

    private fun isSoftwareDecoderName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("c2.google.") ||
            lower.startsWith("omx.ffmpeg.") ||
            lower.startsWith("lib") ||
            lower.startsWith("ffmpeg")
    }
}
