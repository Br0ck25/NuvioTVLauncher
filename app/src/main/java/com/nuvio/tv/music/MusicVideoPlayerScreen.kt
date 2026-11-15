package com.nuvio.tv.music

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The full-screen YouTube music video for the current song. Music playback is paused before this
 * opens; the video brings its own audio. Leaving the screen releases the video player and returns
 * to Now Playing with the song exactly where it was.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MusicVideoPlayerScreen(
    video: Song,
    startPositionMs: Long,
    onClose: (videoPositionMs: Long) -> Unit,
    onSkip: (direction: Int) -> Unit = {},
    onSelectQueue: (index: Int) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val musicState by MusicPlayer.state.collectAsState()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(startPositionMs.coerceAtLeast(0L)) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    val showControls = {
        controlsVisible = true
        controlsTick++
    }

    LaunchedEffect(controlsTick, controlsVisible, loading, error, isPlaying, showQueue) {
        if (showQueue) return@LaunchedEffect
        if (!controlsVisible || loading || error != null || !isPlaying) return@LaunchedEffect
        delay(3_000L)
        controlsVisible = false
    }

    BackHandler(enabled = !showQueue) { onClose(positionMs) }
    BackHandler(enabled = showQueue) { showQueue = false }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            player?.release()
            player = null
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(video.videoId) {
        loading = true
        error = null
        ended = false
        isPlaying = false
        durationMs = 0L
        positionMs = startPositionMs.coerceAtLeast(0L)
        val previous = player
        player = null
        previous?.release()
        val stream = withContext(Dispatchers.IO) {
            runCatching { MusicStreamResolver.resolve(video.videoId) }.getOrNull()
        }
        if (stream == null) {
            error = "This music video couldn't be loaded."
            loading = false
            return@LaunchedEffect
        }
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1920, 1080)
                    .setMaxVideoBitrate(8_000_000)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            )
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(stream.headers)
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        ended = state == Player.STATE_ENDED
                        if (state == Player.STATE_READY) {
                            durationMs = duration.coerceAtLeast(0L)
                            loading = false
                        }
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error = e.message ?: "This music video couldn't be played."
                        loading = false
                    }
                })
            }
        player = exo
        exo.setMediaItem(MediaItem.fromUri(stream.url))
        exo.prepare()
        if (startPositionMs > 0L) exo.seekTo(startPositionMs)
        exo.playWhenReady = true
    }

    LaunchedEffect(player) {
        while (true) {
            player?.let {
                positionMs = it.currentPosition.coerceAtLeast(0L)
                durationMs = it.duration.coerceAtLeast(0L)
                isPlaying = it.isPlaying
            }
            delay(250L)
        }
    }

    LaunchedEffect(controlsVisible, showQueue) {
        if (showQueue) return@LaunchedEffect
        if (controlsVisible) {
            // A couple of frames: the controls are only attached once the enter animation
            // starts. Retrying also avoids leaving the remote on the bare surface.
            repeat(4) {
                withFrameNanos { }
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        } else {
            // The buttons leave the composition while hidden, taking the focus with them.
            // Hand it to the full-screen surface so the next key can bring the controls back.
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Back belongs to the BackHandler: it leaves the video and resumes the song,
                // even while the controls are hidden. The queue overlay owns its own keys.
                if (event.key == Key.Back) return@onPreviewKeyEvent false
                if (showQueue) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && !controlsVisible) {
                    // First press only wakes the controls; consuming it stops the same press
                    // from also toggling play on the focused button behind the overlay.
                    showControls()
                    true
                } else {
                    if (event.type == KeyEventType.KeyDown) showControls()
                    false
                }
            }
    ) {
        player?.let { exo ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        this.player = exo
                    }
                },
                update = { it.player = exo },
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                        )
                    )
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
                        )
                    )
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            VideoCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back to Now Playing",
                onClick = { onClose(positionMs) }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MUSIC VIDEO",
                    color = MusicPalette.accentTeal,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = video.title,
                    color = Color.White,
                    fontFamily = MusicDisplay,
                    fontSize = 28.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = video.artist,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        }

        if (loading) {
            CircularProgressIndicator(
                color = MusicPalette.accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(42.dp)
            )
        }

        error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Video unavailable",
                    color = Color.White,
                    fontFamily = MusicDisplay,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(18.dp))
                LibraryActionButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Back to song",
                    onClick = { onClose(positionMs) }
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut()
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val duration = durationMs.coerceAtLeast(1L)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMusicTime(positionMs),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp
                )
                Text(
                    text = formatMusicTime(durationMs),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((positionMs.toFloat() / duration).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MusicPalette.accent)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                VideoCircleButton(
                    icon = Icons.Default.SkipPrevious,
                    label = "Previous track",
                    onClick = { onSkip(-1) }
                )
                VideoCircleButton(
                    icon = Icons.Default.Replay10,
                    label = "Back 10 seconds",
                    onClick = {
                        player?.let {
                            it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L))
                        }
                    }
                )
                VideoCircleButton(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.focusRequester(playFocus),
                    onClick = {
                        player?.let {
                            when {
                                it.playbackState == Player.STATE_ENDED -> {
                                    it.seekTo(0L)
                                    it.play()
                                }
                                it.isPlaying -> it.pause()
                                else -> it.play()
                            }
                        }
                    }
                )
                VideoCircleButton(
                    icon = Icons.Default.Forward10,
                    label = "Forward 10 seconds",
                    onClick = {
                        player?.let {
                            val max = it.duration.takeIf { d -> d > 0L } ?: Long.MAX_VALUE
                            it.seekTo((it.currentPosition + 10_000L).coerceAtMost(max))
                        }
                    }
                )
                VideoCircleButton(
                    icon = Icons.Default.SkipNext,
                    label = "Next track",
                    onClick = { onSkip(1) }
                )
                VideoCircleButton(
                    icon = Icons.Default.Shuffle,
                    label = if (musicState.shuffle) "Shuffle on" else "Shuffle",
                    active = musicState.shuffle,
                    onClick = { MusicPlayer.setShuffle(!musicState.shuffle) }
                )
                VideoCircleButton(
                    icon = if (musicState.repeatMode == RepeatMode.ONE) {
                        Icons.Default.RepeatOne
                    } else {
                        Icons.Default.Repeat
                    },
                    label = when (musicState.repeatMode) {
                        RepeatMode.OFF -> "Repeat"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    active = musicState.repeatMode != RepeatMode.OFF,
                    onClick = { MusicPlayer.cycleRepeat() }
                )
                if (musicState.queue.isNotEmpty()) {
                    VideoCircleButton(
                        icon = Icons.Default.QueueMusic,
                        label = "Up next",
                        onClick = { showQueue = true }
                    )
                }
                VideoCircleButton(
                    icon = Icons.Default.Headphones,
                    label = "Switch to audio",
                    onClick = { onClose(positionMs) }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Back resumes the song",
                color = Color.White.copy(alpha = 0.34f),
                fontSize = 10.sp
            )
        }
        }

        if (showQueue) {
            LaunchedEffect(Unit) { runCatching { queueFocus.requestFocus() } }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(horizontal = 56.dp, vertical = 36.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Up next",
                            color = Color.White,
                            fontFamily = MusicDisplay,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Close",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showQueue = false }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(musicState.queue) { index, song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(queueFocus) else Modifier)
                                    .background(
                                        if (index == musicState.index) MusicPalette.accent.copy(alpha = 0.18f)
                                        else Color.Transparent
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSelectQueue(index)
                                        showQueue = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = song.title,
                                    color = if (index == musicState.index) MusicPalette.accent else Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                song.durationText?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(text = it, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCircleButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    active -> MusicPalette.accent.copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.14f)
                }
            )
            .border(
                2.dp,
                when {
                    focused -> Color.White
                    active -> MusicPalette.accent.copy(alpha = 0.75f)
                    else -> Color.White.copy(alpha = 0.22f)
                },
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                focused -> MusicPalette.background
                active -> MusicPalette.accent
                else -> Color.White
            },
            modifier = Modifier.size(23.dp)
        )
    }
}
