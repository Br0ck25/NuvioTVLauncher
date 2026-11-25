package com.nuvio.tv.ui.screens.player

import kotlin.math.roundToInt

/*
 * Manual zoom: width, height and position of the picture, on top of the aspect mode.
 *
 * Adapted from TuvoraTV's PlayerPreferencePolicy (GPL-3.0, https://github.com/paradox-kush/TuvoraTV).
 * Pure Kotlin: no Android, no player, no storage.
 */

/**
 * [scaleX]/[scaleY] multiply the picture the aspect mode produced; [panX]/[panY] move it by a
 * fraction of the on-screen (scaled) picture.
 */
data class VideoZoom(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    val isIdentity: Boolean
        get() = VideoZoomPolicy.normalize(this) == IDENTITY

    companion object {
        val IDENTITY = VideoZoom()
    }
}

enum class VideoZoomAxis { Both, Width, Height, PanX, PanY }

/** What to set on the video view: its scale and its offset in pixels. */
data class SurfaceZoomTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float
)

object VideoZoomPolicy {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f
    const val SCALE_STEP = 0.05f
    const val MAX_PAN = 0.5f
    const val PAN_STEP = 0.02f

    fun normalize(zoom: VideoZoom): VideoZoom = VideoZoom(
        scaleX = round2(zoom.scaleX.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        scaleY = round2(zoom.scaleY.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        panX = round2(zoom.panX.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN)),
        panY = round2(zoom.panY.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN))
    )

    fun adjust(zoom: VideoZoom, axis: VideoZoomAxis, steps: Int): VideoZoom {
        val scaleDelta = SCALE_STEP * steps
        val panDelta = PAN_STEP * steps
        val next = when (axis) {
            VideoZoomAxis.Both -> zoom.copy(scaleX = zoom.scaleX + scaleDelta, scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.Width -> zoom.copy(scaleX = zoom.scaleX + scaleDelta)
            VideoZoomAxis.Height -> zoom.copy(scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.PanX -> zoom.copy(panX = zoom.panX + panDelta)
            VideoZoomAxis.PanY -> zoom.copy(panY = zoom.panY + panDelta)
        }
        return normalize(next)
    }

    /**
     * The view transform: the aspect mode's own scale ([baseScaleX]/[baseScaleY]) times the zoom,
     * and the pan as a fraction of the resulting on-screen picture.
     */
    fun surfaceTransform(
        zoom: VideoZoom,
        widthPx: Int,
        heightPx: Int,
        baseScaleX: Float = 1f,
        baseScaleY: Float = 1f
    ): SurfaceZoomTransform {
        val z = normalize(zoom)
        val sx = baseScaleX * z.scaleX
        val sy = baseScaleY * z.scaleY
        return SurfaceZoomTransform(
            scaleX = sx,
            scaleY = sy,
            translationX = z.panX * widthPx.coerceAtLeast(0) * sx,
            translationY = z.panY * heightPx.coerceAtLeast(0) * sy
        )
    }

    fun scaleLabel(zoom: VideoZoom): String {
        val z = normalize(zoom)
        val w = (z.scaleX * 100).roundToInt()
        val h = (z.scaleY * 100).roundToInt()
        return if (w == h) "$w%" else "$w% × $h%"
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this
    private fun round2(value: Float): Float = (value * 100f).roundToInt() / 100f
}
