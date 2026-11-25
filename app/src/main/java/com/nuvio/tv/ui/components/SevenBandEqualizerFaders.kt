package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import com.nuvio.tv.core.audio.SevenBandEqualizerSettings

/** TV-remote-friendly graphic EQ faders; Up/Down changes a band in 1 dB steps. */
@Composable
internal fun SevenBandEqualizerFaders(
    gainsDb: List<Int>,
    onBandChange: (index: Int, gainDb: Int) -> Unit,
    accent: Color,
    text: Color,
    muted: Color,
    trackHeight: Dp = 174.dp,
    onMenuPressed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    onMenuPressed()
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        SevenBandEqualizerSettings.frequenciesHz.forEachIndexed { index, frequency ->
            val gain = gainsDb.getOrElse(index) { 0 }.coerceIn(-12, 12)
            var focused by remember(index) { mutableStateOf(false) }
            val label = when (frequency) {
                125 -> "≤125"
                8_000 -> "≥8k"
                else -> if (frequency >= 1_000) "${frequency / 1_000}k" else frequency.toString()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (gain < SevenBandEqualizerSettings.MAX_GAIN_DB) onBandChange(index, gain + 1)
                                true
                            }
                            Key.DirectionDown -> {
                                if (gain > SevenBandEqualizerSettings.MIN_GAIN_DB) onBandChange(index, gain - 1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                onBandChange(index, 0)
                                true
                            }
                            else -> false
                        }
                    }
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(gain.toFloat(), -12f..12f)
                    }
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = label, color = if (focused) text else muted)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .padding(vertical = 8.dp)
                ) {
                    val thumbHeight = 20.dp
                    val progress = (gain + 12) / 24f
                    val thumbY = (maxHeight - thumbHeight) * (1f - progress)
                    Canvas(Modifier.fillMaxWidth().height(maxHeight)) {
                        val x = size.width / 2f
                        drawLine(
                            color = muted.copy(alpha = 0.65f),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = 3.dp.toPx()
                        )
                        val centerY = size.height * 0.5f
                        drawLine(
                            color = text.copy(alpha = 0.35f),
                            start = androidx.compose.ui.geometry.Offset(0f, centerY),
                            end = androidx.compose.ui.geometry.Offset(size.width, centerY),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = thumbY)
                            .size(width = 38.dp, height = thumbHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) text else accent)
                    )
                }
                Text(
                    text = if (gain > 0) "+${gain} dB" else "$gain dB",
                    color = if (focused) text else muted
                )
            }
        }
    }
}
