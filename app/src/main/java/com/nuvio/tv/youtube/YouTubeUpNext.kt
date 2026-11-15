package com.nuvio.tv.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.screens.player.PlaybackTimelineState
import com.nuvio.tv.R

/** When the card appears, and when the next video is picked so it's ready in time. */
private const val SHOW_WITHIN_MS = 12_000L
private const val PICK_WITHIN_MS = 45_000L

/**
 * A small card at the bottom right near the end of a YouTube video, showing what plays next and how
 * long until it does. Only while autoplay is on.
 */
@Composable
fun BoxScope.YouTubeUpNext(
    videoId: String,
    viewModel: YouTubeViewModel,
    timeline: PlaybackTimelineState,
    /** The video playing right now is a live stream: nothing is stringResource(R.string.onetube_up_next_lower). */
    isLiveStream: Boolean = false,
    /** While the video is repeating, nothing is coming up next. */
    looping: Boolean = false
) {
    val context = LocalContext.current
    val next by viewModel.upNext.collectAsState()
    val remaining = timeline.duration - timeline.currentPosition
    val inRange = !isLiveStream && !timeline.isLive && timeline.duration > 0 && remaining in 1..PICK_WITHIN_MS

    // Pick the next video early enough that it's ready, and its stream is looked up.
    LaunchedEffect(videoId, inRange) {
        if (inRange && !looping && YouTubeSettings.autoplayNext(context)) viewModel.prepareNext(videoId)
    }

    val item = next
    if (looping || item == null || !inRange || remaining > SHOW_WITHIN_MS || !YouTubeSettings.autoplayNext(context)) return

    val seconds = ((remaining + 999) / 1000).toInt().coerceAtLeast(1)
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            // Above the seek bar, so it doesn't sit on the controls.
            .padding(end = 28.dp, bottom = 100.dp)
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6111114))
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
        }
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(stringResource(R.string.onetube_up_next_seconds, seconds), color = Color(0xFFBBBBBB), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(
                item.title,
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
