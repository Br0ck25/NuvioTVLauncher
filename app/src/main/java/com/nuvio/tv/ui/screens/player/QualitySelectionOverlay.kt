package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Video quality picker, shown for streams that offer several sizes (YouTube, adaptive DASH/HLS).
 * Height 0 is "Auto", which lets the player choose from the connection speed.
 */
@Composable
internal fun QualitySelectionOverlay(
    visible: Boolean,
    options: List<VideoQualityOption>,
    selectedHeight: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val rows = listOf(VideoQualityOption(0, "Auto")) + options
    val focusRequester = remember { FocusRequester() }
    // The row to open on: the current choice, or Auto.
    val startIndex = rows.indexOfFirst { it.height == selectedHeight }.coerceAtLeast(0)

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focusRequester.requestFocus() }
    }

    Box(modifier.background(Color(0x66000000)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xF2111114))
                .padding(24.dp)
        ) {
            Text("Video quality", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows.size) { i ->
                    val row = rows[i]
                    var focused by remember { mutableStateOf(false) }
                    val isCurrent = row.height == selectedHeight
                    Text(
                        text = if (isCurrent) "${row.label}   ✓" else row.label,
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Color(0x33FFFFFF) else Color.Transparent)
                            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                            .then(if (i == startIndex) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onSelected(row.height) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
