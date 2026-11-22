package com.nuvio.tv.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Optional, device-local RAM and playback-buffer overlay.
 *
 * RAM is sampled here; the buffer comes from [GlobalPlaybackStats], which the player updates with
 * its own buffered-ahead amount. When no player screen is alive, the buffer line says "idle".
 */
object GlobalStatsOverlaySettings {
    private const val PREFS = "nuvio_global_stats_overlay"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _enabled.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

data class PlaybackBufferSnapshot(
    val active: Boolean = false,
    val playing: Boolean = false,
    val aheadMs: Long = 0L
)

object GlobalPlaybackStats {
    private val _buffer = MutableStateFlow(PlaybackBufferSnapshot())
    val buffer: StateFlow<PlaybackBufferSnapshot> = _buffer.asStateFlow()

    fun update(aheadMs: Long, playing: Boolean) {
        _buffer.value = PlaybackBufferSnapshot(
            active = true,
            playing = playing,
            aheadMs = aheadMs.coerceAtLeast(0L)
        )
    }

    fun clear() {
        _buffer.value = PlaybackBufferSnapshot()
    }
}

@Composable
fun BoxScope.GlobalStatsOverlay() {
    val context = LocalContext.current
    val enabled by GlobalStatsOverlaySettings.enabled.collectAsState()
    val buffer by GlobalPlaybackStats.buffer.collectAsState()

    LaunchedEffect(Unit) { GlobalStatsOverlaySettings.init(context) }
    if (!enabled) return

    var memoryLine by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            memoryLine = withContext(Dispatchers.IO) { readMemory(context) }
            delay(2_000L)
        }
    }

    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 72.dp, end = 18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = memoryLine,
            color = Color(0xFFE8E8E8),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = when {
                !buffer.active -> "buffer  idle"
                else -> String.format(
                    Locale.US,
                    "buffer  %.1fs%s",
                    buffer.aheadMs / 1000.0,
                    if (buffer.playing) "  playing" else "  paused"
                )
            },
            color = if (buffer.active && buffer.aheadMs < 5_000L) Color(0xFFFFB300)
            else Color(0xFFE8E8E8),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

private fun readMemory(context: Context): String {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = ActivityManager.MemoryInfo()
    manager?.getMemoryInfo(info)
    val runtime = Runtime.getRuntime()
    val appMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
    val totalMb = info.totalMem / (1024L * 1024L)
    val availMb = info.availMem / (1024L * 1024L)
    val pssMb = runCatching {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        memory.totalPss / 1024L
    }.getOrDefault(-1L)
    val appText = if (pssMb >= 0) "$pssMb MB pss" else "$appMb MB heap"
    return if (totalMb > 0) {
        "RAM  $appText   free $availMb / $totalMb MB"
    } else {
        "RAM  $appText"
    }
}
