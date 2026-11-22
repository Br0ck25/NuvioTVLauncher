package com.nuvio.tv.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

/**
 * The live chat of a live stream, drawn over the picture as messages arrive, newest at the bottom.
 * Read-only: it has no focus, so the remote keeps working on the player.
 *
 * [appearance] is passed in rather than read here, so the chat settings panel can change position,
 * size, colour and background and have the window update at once.
 */
@Composable
fun BoxScope.YouTubeChatOverlay(viewModel: YouTubePlayerViewModel, appearance: ChatAppearance) {
    val lines by viewModel.chat.collectAsState()

    val alignment = when (appearance.position) {
        "top_start" -> Alignment.TopStart
        "top_center" -> Alignment.TopCenter
        "top_end" -> Alignment.TopEnd
        "center_start" -> Alignment.CenterStart
        "center" -> Alignment.Center
        "center_end" -> Alignment.CenterEnd
        "bottom_start" -> Alignment.BottomStart
        "bottom_center" -> Alignment.BottomCenter
        "bottom_end" -> Alignment.BottomEnd
        else -> Alignment.CenterEnd
    }
    val width: androidx.compose.ui.unit.Dp
    val fontSize: androidx.compose.ui.unit.TextUnit
    val visibleLines: Int
    when (appearance.size) {
        "small" -> { width = 250.dp; fontSize = 11.sp; visibleLines = 8 }
        "large" -> { width = 420.dp; fontSize = 14.sp; visibleLines = 16 }
        else -> { width = 320.dp; fontSize = 12.sp; visibleLines = 12 }
    }
    val textColor = Color(appearance.textColor)
    val background = Color.Black.copy(alpha = appearance.backgroundOpacity / 100f)

    Column(
        Modifier
            .align(alignment)
            .padding(24.dp)
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(10.dp)
    ) {
        if (lines.isEmpty()) {
            Text(stringResource(R.string.onetube_waiting_chat), color = textColor.copy(alpha = 0.6f), fontSize = fontSize)
        }
        lines.takeLast(visibleLines).forEach { line ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color(appearance.nameColor), fontWeight = FontWeight.SemiBold)) {
                        append(line.author)
                    }
                    append("  ")
                    withStyle(SpanStyle(color = textColor)) { append(line.message) }
                },
                fontSize = fontSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
