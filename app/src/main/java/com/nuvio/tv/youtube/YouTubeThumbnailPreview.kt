package com.nuvio.tv.youtube

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import kotlinx.coroutines.delay

/** How long a card plays its preview before it goes back to the still thumbnail. */
private const val PREVIEW_LIMIT_MS = 30_000L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun YouTubeThumbnailPreview(item: YtItem, viewModel: YouTubeViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    var playable by remember(item.videoId) { mutableStateOf<YtPlayable?>(null) }
    LaunchedEffect(item.videoId, resumed) {
        playable = null
        if (resumed) {
            delay(1500)
            playable = viewModel.previewVideo(item)
        }
    }
    // Leaving the card stops a lookup it started, so it can't hold up the next video.
    DisposableEffect(item.videoId) {
        onDispose { viewModel.cancelPreview(item) }
    }
    val video = playable ?: return
    if (!resumed) return
    var ready by remember(video.url) { mutableStateOf(false) }
    val player = remember(video.url) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(video.headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(video.url))
                prepare()
                playWhenReady = true
            }
    }
    // A preview that is left in view should not keep streaming (or playing sound) forever.
    LaunchedEffect(player) {
        delay(PREVIEW_LIMIT_MS)
        playable = null
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            private var selectedOriginalAudio = false
            override fun onTracksChanged(tracks: Tracks) {
                if (selectedOriginalAudio) return
                val group = tracks.groups.firstOrNull { group ->
                    group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { index ->
                        val format = group.getTrackFormat(index)
                        group.isTrackSupported(index) && listOfNotNull(format.language, format.label, format.id)
                            .any { it.contains("original", ignoreCase = true) }
                    }
                } ?: return
                val indices = (0 until group.length).filter { index ->
                    val format = group.getTrackFormat(index)
                    group.isTrackSupported(index) && listOfNotNull(format.language, format.label, format.id)
                        .any { it.contains("original", ignoreCase = true) }
                }
                selectedOriginalAudio = true
                android.util.Log.d("YouTubePreview", "Selecting original audio: ${group.getTrackFormat(indices.first()).language}")
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices)).build()
            }
            override fun onRenderedFirstFrame() { ready = true }
        }
        player.addListener(listener)
        listener.onTracksChanged(player.currentTracks)
        onDispose {
            player.removeListener(listener)
            runCatching { player.stop() }
            player.release()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null }
    )
}
