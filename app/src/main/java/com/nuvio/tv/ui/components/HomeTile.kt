package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live TV fork: the one tile size the Home rows share — Continue Watching's 16:9 card.
 *
 * Collection folders, Upcoming and the Live TV row read it so they line up. Upcoming and Live TV
 * cards are two tiles wide ([wideWidth]).
 */
@Immutable
data class HomeTile(val width: Dp, val height: Dp, val gap: Dp) {
    /** Two tiles and the gap between them: where a row of these lines up exactly. */
    val wideWidth: Dp get() = width * 2 + gap
}

/**
 * The tile the Home layout on screen is using. Each layout publishes its Continue Watching size
 * here, so screens outside Home and the shared cards can follow it. Null until Home has been drawn.
 */
object HomeTiles {
    private val current = MutableStateFlow<HomeTile?>(null)

    /** The user's Tile size (Layout settings), 1f by default. */
    private val scaleState = MutableStateFlow(1f)
    val scale: StateFlow<Float> = scaleState
    private val liveTvScaleState = MutableStateFlow(1f)
    val liveTvScale: StateFlow<Float> = liveTvScaleState
    private val upcomingScaleState = MutableStateFlow(1f)
    val upcomingScale: StateFlow<Float> = upcomingScaleState

    /** The published tile with the user's size applied. */
    private val scaledState = MutableStateFlow<HomeTile?>(null)
    val tile: StateFlow<HomeTile?> = scaledState

    private var scalable = true

    fun publish(tile: HomeTile, scalable: Boolean = true) {
        if (current.value != tile || this.scalable != scalable) {
            this.scalable = scalable
            current.value = tile
            update()
        }
    }

    fun setScale(value: Float) {
        if (scaleState.value != value) {
            scaleState.value = value
            update()
        }
    }

    fun setLiveTvScale(value: Float) { liveTvScaleState.value = value }
    fun setUpcomingScale(value: Float) { upcomingScaleState.value = value }

    private fun update() {
        val base = current.value
        val s = if (scalable) scaleState.value else 1f
        scaledState.value = base?.let { HomeTile(it.width * s, it.height * s, it.gap) }
    }
}

@Composable
fun rememberHomeTile(): HomeTile? {
    val tile by HomeTiles.tile.collectAsStateWithLifecycle()
    return tile
}

@Composable
fun rememberHomeTileScale(): Float {
    val scale by HomeTiles.scale.collectAsStateWithLifecycle()
    return scale
}

@Composable
fun rememberLiveTvHomeRowScale(): Float {
    val scale by HomeTiles.liveTvScale.collectAsStateWithLifecycle()
    return scale
}

@Composable
fun rememberUpcomingHomeRowScale(): Float {
    val scale by HomeTiles.upcomingScale.collectAsStateWithLifecycle()
    return scale
}
