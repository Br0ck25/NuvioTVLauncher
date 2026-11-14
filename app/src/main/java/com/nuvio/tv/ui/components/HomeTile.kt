package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one tile size the Home rows share: Continue Watching's 16:9 card. Apps, collections and the
 * Apps page use it as it is; Upcoming and Live TV cards are two tiles wide (and keep their own height).
 */
@Immutable
data class HomeTile(val width: Dp, val height: Dp, val gap: Dp) {
    /** Two tiles and the gap between them: where a row of these lines up exactly. */
    val wideWidth: Dp get() = width * 2 + gap
}

/**
 * The tile the Home layout on screen is using. Each layout publishes its Continue Watching size here, so
 * screens outside Home (the Apps page) and the shared cards can follow it. Null until Home has been drawn.
 */
object HomeTiles {
    private val current = MutableStateFlow<HomeTile?>(null)
    val tile: StateFlow<HomeTile?> = current

    fun publish(tile: HomeTile) {
        if (current.value != tile) current.value = tile
    }
}

@Composable
fun rememberHomeTile(): HomeTile? {
    val tile by HomeTiles.tile.collectAsStateWithLifecycle()
    return tile
}
