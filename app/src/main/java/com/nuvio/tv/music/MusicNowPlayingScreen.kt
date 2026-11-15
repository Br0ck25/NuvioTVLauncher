package com.nuvio.tv.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NowPlayingTab(val label: String) {
    LYRICS("Lyrics"),
    QUEUE("Up next"),
    INFO("Info")
}

@Composable
internal fun MusicNowPlayingScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMusicVideo: (Song, Long) -> Unit,
    onOpenArtist: (Song) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by MusicPlayer.state.collectAsState()
    val liked by MusicLibrary.liked.collectAsState()
    val playlists by MusicLibrary.playlists.collectAsState()
    val downloads by MusicDownloads.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lyricsViewModel: MusicLyricsViewModel = hiltViewModel()
    val lyricsState by lyricsViewModel.state.collectAsState()
    val song = state.song
    val isLiked = song?.videoId?.let { id -> liked.any { it.videoId == id } } == true
    val downloadEntry = song?.videoId?.let { downloads.entry(it) }
    val downloadStarting = song?.videoId?.let { downloads.starting.containsKey(it) } == true
    val downloadActive = downloadStarting ||
        downloadEntry?.status == MusicDownloadStatus.DOWNLOADING ||
        downloadEntry?.status == MusicDownloadStatus.QUEUED ||
        downloadEntry?.status == MusicDownloadStatus.PAUSED
    val downloaded = downloadEntry?.status == MusicDownloadStatus.COMPLETE
    val downloadProgress: Float? = when {
        downloaded -> 1f
        downloadStarting -> 0f
        downloadEntry?.status == MusicDownloadStatus.DOWNLOADING ||
            downloadEntry?.status == MusicDownloadStatus.QUEUED ||
            downloadEntry?.status == MusicDownloadStatus.PAUSED ->
            downloadEntry.progress
        else -> null
    }
    val playFocus = remember { FocusRequester() }
    val firstPickerFocus = remember { FocusRequester() }
    val artistFocus = remember { FocusRequester() }
    val likeFocus = remember { FocusRequester() }
    val canOpenArtist = song?.artist?.isNotBlank() == true
    var artistFocused by remember { mutableStateOf(false) }
    // Account/home rows don't always carry the album; the watch queue entry does.
    var albumInfo by remember(song?.videoId) { mutableStateOf<Pair<String?, String?>?>(null) }
    LaunchedEffect(song?.videoId, song?.albumId, song?.albumName) {
        val current = song ?: return@LaunchedEffect
        if ((!current.albumId.isNullOrBlank() && !current.albumName.isNullOrBlank()) ||
            current.localUri != null
        ) {
            return@LaunchedEffect
        }
        albumInfo = withContext(Dispatchers.IO) {
            YtMusicRepository.trackLinks(current.videoId).getOrNull()
                ?.let { it.albumId to it.albumName }
        }
    }
    val albumId = song?.albumId?.takeIf { it.isNotBlank() } ?: albumInfo?.first
    val albumName = song?.albumName?.takeIf { it.isNotBlank() } ?: albumInfo?.second
    val canOpenAlbum = albumId != null && song?.localUri == null
    val albumLabel = albumName?.takeIf { it.isNotBlank() }
        ?: if (canOpenAlbum) "Album" else null
    val albumFocus = remember { FocusRequester() }
    var albumFocused by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val colors = musicAuroraColors(song?.thumbnailUrl ?: song?.title)
    val playInteraction = remember { MutableInteractionSource() }
    val playFocused by playInteraction.collectIsFocusedAsState()
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    var nowTab by remember { mutableStateOf(NowPlayingTab.LYRICS) }

    DisposableEffect(Unit) {
        MusicPlayer.setFastProgress(true)
        onDispose { MusicPlayer.setFastProgress(false) }
    }

    LaunchedEffect(song?.videoId) {
        lyricsExpanded = false
        nowTab = NowPlayingTab.LYRICS
        lyricsViewModel.load(song)
        MusicPlayer.prepareVideoForCurrentTrack()
    }

    LaunchedEffect(showPlaylistPicker) {
        if (showPlaylistPicker) {
            withFrameNanos { }
            runCatching { firstPickerFocus.requestFocus() }
        }
    }

    BackHandler(enabled = !showPlaylistPicker && !lyricsExpanded) {
        onClose()
    }

    BackHandler(enabled = showPlaylistPicker) {
        showPlaylistPicker = false
    }

    BackHandler(enabled = lyricsExpanded && !showPlaylistPicker) {
        lyricsExpanded = false
    }

    LaunchedEffect(windowInfo) {
        // A dialog's window receives focus after its content composes. Wait for that focus,
        // then hand the remote to the play button.
        snapshotFlow { windowInfo.isWindowFocused }.collect { isWindowFocused ->
            if (isWindowFocused) {
                runCatching { playFocus.requestFocus() }
            }
        }
    }

    Box(modifier.fillMaxSize().background(MusicPalette.background)) {
        MusicAuroraBackground(
            seed = song?.thumbnailUrl ?: song?.title,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x5508070B),
                            Color(0xCC08070B),
                            MusicPalette.background
                        )
                    )
                )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NowHeaderButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    label = "Close",
                    modifier = Modifier.focusProperties { down = playFocus },
                    onClick = onClose
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "NOW PLAYING",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    letterSpacing = 2.4.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (song != null && song.localUri.isNullOrBlank()) {
                    NowHeaderButton(
                        icon = when {
                            downloaded -> Icons.Default.DownloadDone
                            downloadActive || downloadStarting -> Icons.Default.Close
                            downloadEntry?.status == MusicDownloadStatus.FAILED ->
                                Icons.Default.Refresh
                            else -> Icons.Default.Download
                        },
                        label = when {
                            downloaded -> "Downloaded"
                            downloadActive || downloadStarting -> "Cancel download"
                            downloadEntry?.status == MusicDownloadStatus.FAILED ->
                                "Retry download"
                            else -> "Download this track"
                        },
                        active = downloaded,
                        progress = downloadProgress,
                        onClick = {
                            when {
                                downloaded -> Unit
                                downloadActive || downloadStarting ->
                                    MusicDownloads.cancel(song.videoId)
                                downloadEntry?.status == MusicDownloadStatus.FAILED ->
                                    MusicDownloads.retry(downloadEntry)
                                else -> MusicDownloads.download(song)
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                NowHeaderButton(
                    icon = if (lyricsExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    label = if (lyricsExpanded) "Exit canvas" else "Canvas lyrics",
                    active = lyricsExpanded,
                    onClick = {
                        lyricsExpanded = !lyricsExpanded
                        if (lyricsExpanded) nowTab = NowPlayingTab.LYRICS
                    }
                )
                Spacer(Modifier.width(8.dp))
                NowHeaderButton(
                    icon = Icons.Default.Settings,
                    label = "Music settings",
                    onClick = onOpenSettings
                )
            }

            Spacer(Modifier.height(10.dp))

            if (lyricsExpanded) {
                LyricsPanel(
                    state = lyricsState,
                    positionMs = state.positionMs,
                    expanded = true,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.weight(0.92f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MusicVinyl(
                                colors = colors,
                                modifier = Modifier
                                    .size(246.dp)
                                    .offset(x = 44.dp, y = 8.dp),
                                spinning = state.isPlaying
                            )
                            MusicArtwork(
                                url = song?.thumbnailUrl,
                                modifier = Modifier.size(300.dp),
                                cornerRadius = 20.dp,
                                iconSize = 72.dp,
                                artworkPx = 1200
                            )
                        }
                    }

                    Column(
                        Modifier.weight(1.08f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (song?.localUri != null) {
                                "PLAYING FROM THIS DEVICE"
                            } else {
                                "PLAYING FROM NUVIO MUSIC"
                            },
                            color = MusicPalette.accentTeal,
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = song?.title ?: "Nothing playing",
                            color = MusicPalette.text,
                            fontFamily = MusicDisplay,
                            fontSize = 42.sp,
                            lineHeight = 44.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        val artistName = song?.artist.orEmpty()
                        val artistModifier = if (canOpenArtist) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (artistFocused) Color.White.copy(alpha = 0.10f)
                                    else Color.Transparent
                                )
                                .focusRequester(artistFocus)
                                .onFocusChanged { artistFocused = it.isFocused }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionDown
                                    ) {
                                        val next = if (canOpenAlbum) albumFocus else likeFocus
                                        runCatching { next.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .clickable { song?.let(onOpenArtist) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        }
                        Box(artistModifier) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = artistName,
                                    color = if (artistFocused) MusicPalette.accent
                                    else Color.White.copy(alpha = 0.78f),
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Open artist",
                                    tint = if (artistFocused) MusicPalette.accent
                                    else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        albumLabel?.let { name ->
                            Spacer(Modifier.height(3.dp))
                            val albumModifier = if (canOpenAlbum) {
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (albumFocused) Color.White.copy(alpha = 0.10f)
                                        else Color.Transparent
                                    )
                                    .focusRequester(albumFocus)
                                    .onFocusChanged { albumFocused = it.isFocused }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            return@onPreviewKeyEvent false
                                        }
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                val target = if (canOpenArtist) artistFocus else playFocus
                                                runCatching { target.requestFocus() }
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                runCatching { likeFocus.requestFocus() }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .clickable {
                                        song?.let {
                                            onOpenAlbum(it.copy(albumId = albumId, albumName = name))
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            } else {
                                Modifier
                            }
                            Box(albumModifier) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = name,
                                        color = if (albumFocused) MusicPalette.accent
                                        else Color.White.copy(alpha = 0.44f),
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (canOpenAlbum) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.KeyboardArrowRight,
                                            contentDescription = "Open album",
                                            tint = if (albumFocused) MusicPalette.accent
                                            else Color.White.copy(alpha = 0.30f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = if (canOpenArtist) {
                                Modifier.onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionUp
                                    ) {
                                        runCatching { artistFocus.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ) {
                            MusicActionChip(
                                icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                label = if (isLiked) "Liked" else "Like",
                                modifier = Modifier.focusRequester(likeFocus),
                                active = isLiked,
                                onClick = {
                                    song?.let {
                                        val likedNow = MusicLibrary.toggleLike(it)
                                        when {
                                            it.videoId.startsWith("navidrome:") ->
                                                scope.launch(Dispatchers.IO) {
                                                    MusicNavidrome.setStarred(it, likedNow)
                                                }
                                            it.localUri.isNullOrBlank() ->
                                                MusicAccountRepository.syncLike(it, likedNow)
                                        }
                                    }
                                }
                            )
                            MusicActionChip(
                                icon = Icons.Default.PlaylistAdd,
                                label = "Playlist",
                                onClick = { if (song != null) showPlaylistPicker = true }
                            )
                            if (song?.localUri == null) {
                                MusicActionChip(
                                    icon = Icons.Default.Radio,
                                    label = "Radio",
                                    onClick = { song?.let { MusicPlayer.startRadio(it) } }
                                )
                            }
                            MusicActionChip(
                                icon = Icons.Default.Shuffle,
                                label = if (state.shuffle) "Shuffle on" else "Shuffle",
                                active = state.shuffle,
                                onClick = { MusicPlayer.setShuffle(!state.shuffle) }
                            )
                            MusicActionChip(
                                icon = if (state.repeatMode == RepeatMode.ONE) {
                                    Icons.Default.RepeatOne
                                } else {
                                    Icons.Default.Repeat
                                },
                                label = when (state.repeatMode) {
                                    RepeatMode.OFF -> "Repeat"
                                    RepeatMode.ALL -> "Repeat all"
                                    RepeatMode.ONE -> "Repeat one"
                                },
                                active = state.repeatMode != RepeatMode.OFF,
                                onClick = { MusicPlayer.cycleRepeat() }
                            )
                            if (state.videoAvailable && state.videoSong != null && song?.localUri == null) {
                                MusicActionChip(
                                    icon = Icons.Default.Videocam,
                                    label = "Music video",
                                    active = false,
                                    onClick = {
                                        state.videoSong?.let { video ->
                                            MusicPlayer.pause()
                                            onOpenMusicVideo(video, MusicPlayer.videoStartPositionMs())
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        val duration = state.durationMs.coerceAtLeast(1L)
                        NowSeekBar(
                            progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                            onSeekFraction = { fraction ->
                                MusicPlayer.seekTo((fraction * duration).toLong())
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatMusicTime(state.positionMs),
                                color = Color.White.copy(alpha = 0.42f),
                                fontSize = 10.sp
                            )
                            Text(
                                text = formatMusicTime(state.durationMs),
                                color = Color.White.copy(alpha = 0.42f),
                                fontSize = 10.sp
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TransportButton(Icons.Default.SkipPrevious, "Previous") {
                                MusicPlayer.previous()
                            }
                            Spacer(Modifier.width(16.dp))
                            Box(
                                Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(if (playFocused) MusicPalette.accent else Color.White)
                                    .border(
                                        2.dp,
                                        if (playFocused) Color.White else Color.Transparent,
                                        CircleShape
                                    )
                                    .focusRequester(playFocus)
                                    .clickable(
                                        interactionSource = playInteraction,
                                        indication = null
                                    ) { MusicPlayer.toggle() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                                    tint = if (playFocused) Color.White else MusicPalette.background,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            TransportButton(Icons.Default.SkipNext, "Next") {
                                MusicPlayer.next()
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NowPlayingTab.entries.forEach { tab ->
                                NowPlayingTabButton(
                                    label = tab.label,
                                    selected = nowTab == tab,
                                    onClick = { nowTab = tab }
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.10f))
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(top = 7.dp)
                        ) {
                            when (nowTab) {
                                NowPlayingTab.LYRICS -> LyricsPanel(
                                    state = lyricsState,
                                    positionMs = state.positionMs,
                                    expanded = false,
                                    modifier = Modifier.fillMaxSize()
                                )
                                NowPlayingTab.QUEUE -> QueuePanel(
                                    state = state,
                                    playFocus = playFocus
                                )
                                NowPlayingTab.INFO -> NowInfoPanel(
                                    song = song,
                                    state = state,
                                    isLiked = isLiked
                                )
                            }
                        }
                        state.error?.let { error ->
                            Text(
                                text = error,
                                color = MusicPalette.error,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (showPlaylistPicker) {
            PlaylistPickerOverlay(
                playlists = playlists,
                firstFocus = firstPickerFocus,
                onDismiss = { showPlaylistPicker = false },
                onPick = { playlist ->
                    song?.let { MusicLibrary.addToPlaylist(playlist.id, it) }
                    showPlaylistPicker = false
                },
                onCreate = {
                    val created = MusicLibrary.createPlaylist()
                    song?.let { MusicLibrary.addToPlaylist(created.id, it) }
                    showPlaylistPicker = false
                }
            )
        }
    }
}

@Composable
private fun NowHeaderButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    progress: Float? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    active -> MusicPalette.accent
                    else -> Color.White.copy(alpha = 0.09f)
                }
            )
            .border(
                1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.14f),
                CircleShape
            )
            .drawBehind {
                if (progress != null) {
                    val stroke = 2.4.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Color.White.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        color = MusicPalette.accentTeal,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                focused -> MusicPalette.background
                active -> MusicPalette.background
                else -> Color.White.copy(alpha = 0.82f)
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun NowPlayingTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> MusicPalette.surfaceRaised
                    selected -> MusicPalette.surface
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                when {
                    focused -> Color.White
                    selected -> MusicPalette.accent.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label.uppercase(),
            color = if (selected) Color.White else Color.White.copy(alpha = 0.44f),
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) MusicPalette.accent else Color.Transparent)
        )
    }
}

@Composable
private fun QueuePanel(
    state: MusicPlayer.State,
    playFocus: FocusRequester
) {
    if (state.upNext.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            Text(
                text = if (state.repeatMode == RepeatMode.ALL) {
                    "The queue starts over when this ends."
                } else {
                    "This is the last track in the queue."
                },
                color = MusicPalette.textMuted,
                fontSize = 12.sp
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(
            items = state.upNext,
            key = { index, item -> "${item.videoId}#$index" }
        ) { index, item ->
            UpNextRow(
                song = item,
                index = index,
                onDown = if (index == state.upNext.lastIndex) {
                    { runCatching { playFocus.requestFocus() }.isSuccess }
                } else {
                    null
                },
                onClick = { MusicPlayer.jumpTo(state.index + 1 + index) }
            )
        }
    }
}

@Composable
private fun NowSeekBar(
    progress: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeekFraction((progress - 0.015f).coerceIn(0f, 1f))
                        true
                    }
                    Key.DirectionRight -> {
                        onSeekFraction((progress + 0.015f).coerceIn(0f, 1f))
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
            .focusable()
    ) {
        val centerY = size.height / 2f
        val clamped = progress.coerceIn(0f, 1f)
        val knobX = (size.width * clamped).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White.copy(alpha = if (focused) 1f else 0.92f),
            start = Offset(0f, centerY),
            end = Offset(knobX, centerY),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )
        if (focused) {
            drawCircle(
                color = MusicPalette.accent.copy(alpha = 0.28f),
                radius = 12.dp.toPx(),
                center = Offset(knobX, centerY)
            )
        }
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(knobX, centerY)
        )
    }
}

@Composable
private fun NowInfoPanel(song: Song?, state: MusicPlayer.State, isLiked: Boolean) {
    val duration = if (state.durationMs > 0L) {
        formatMusicTime(state.durationMs)
    } else {
        song?.durationText.orEmpty()
    }
    val fields = listOf(
        "Album" to (song?.albumName ?: "Single"),
        "Artist" to (song?.artist ?: "Unknown artist"),
        "Duration" to duration.ifBlank { "—" },
        "Source" to if (song?.localUri != null) "This device" else "Streaming",
        "Format" to if (song?.isVideo == true) "Video upload" else "Catalogue track",
        "Library" to if (isLiked) "Liked" else "Not liked"
    )
    Column(
        Modifier.fillMaxSize().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        fields.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = label.uppercase(),
                            color = Color.White.copy(alpha = 0.38f),
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = value,
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    state: MusicLyricsViewModel.State,
    positionMs: Long,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxHeight()) {
        when (state) {
            MusicLyricsViewModel.State.Idle -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = "Play a track to see its lyrics.",
                    color = MusicPalette.textMuted,
                    fontSize = 13.sp
                )
            }
            MusicLyricsViewModel.State.Loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MusicPalette.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
            is MusicLyricsViewModel.State.Unavailable -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = state.message,
                    color = MusicPalette.textMuted,
                    fontSize = 13.sp
                )
            }
            is MusicLyricsViewModel.State.Ready -> {
                val lyrics = state.lyrics
                val listState = rememberLazyListState()
                val timed = lyrics.hasTiming
                val current = if (timed) {
                    lyrics.lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
                } else {
                    -1
                }
                LaunchedEffect(current, lyrics.lines.size, expanded) {
                    if (timed && current >= 0) {
                        runCatching {
                            val viewport = listState.layoutInfo.viewportSize.height
                            val centerOffset = if (expanded && viewport > 0) {
                                -(viewport / 2 - 60)
                            } else {
                                0
                            }
                            listState.animateScrollToItem(
                                current.coerceIn(0, lyrics.lines.lastIndex.coerceAtLeast(0)),
                                scrollOffset = centerOffset
                            )
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (!timed) {
                        item(key = "lyrics-untimed") {
                            Text(
                                text = "Lyrics aren't time-synced for this track.",
                                color = MusicPalette.textMuted,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "$index:${line.timeMs}" }
                    ) { index, line ->
                        if (line.isGap) {
                            Spacer(Modifier.height(if (expanded) 16.dp else 10.dp))
                        } else {
                            val isCurrent = index == current
                            val nextLineTime = lyrics.lines.getOrNull(index + 1)?.timeMs
                            val baseColor = when {
                                !timed -> Color.White.copy(alpha = 0.62f)
                                line.timeMs < positionMs -> Color.White.copy(alpha = 0.20f)
                                else -> Color.White.copy(alpha = 0.36f)
                            }
                            // A left-to-right wipe instead of a whole-line fill. Word timings
                            // drive it where the provider has them; line-synced lyrics sweep
                            // across their own slot up to the next stamp.
                            val sweepTarget = if (isCurrent) {
                                if (line.isWordSynced) {
                                    line.revealedChars(positionMs) /
                                        line.text.length.coerceAtLeast(1)
                                } else {
                                    val start = line.timeMs
                                    val end = line.sungUntilMs ?: nextLineTime ?: (start + 4_000L)
                                    (positionMs - start).toFloat() /
                                        (end - start).coerceAtLeast(1L)
                                }.coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            // The player reports position twice a second; tween between
                            // those updates so the wipe travels instead of stepping.
                            val sweep by animateFloatAsState(
                                targetValue = sweepTarget,
                                animationSpec = tween(durationMillis = 200, easing = LinearEasing),
                                label = "lyric-sweep"
                            )
                            val fontSize = when {
                                isCurrent && expanded -> 38.sp
                                isCurrent -> 22.sp
                                expanded -> 24.sp
                                else -> 16.sp
                            }
                            val lineHeight = if (expanded) 44.sp else 24.sp
                            val weight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            var textLayout by remember(line.text, fontSize, expanded) {
                                mutableStateOf<TextLayoutResult?>(null)
                            }
                            Box(Modifier.fillMaxWidth()) {
                                Text(
                                    text = line.text,
                                    color = if (isCurrent) {
                                        Color.White.copy(alpha = 0.38f)
                                    } else {
                                        baseColor
                                    },
                                    fontFamily = if (expanded) MusicDisplay else null,
                                    fontSize = fontSize,
                                    fontWeight = weight,
                                    lineHeight = lineHeight,
                                    onTextLayout = { textLayout = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val layout = textLayout
                                if (isCurrent && layout != null) {
                                    Canvas(Modifier.matchParentSize()) {
                                        val totalChars = line.text.length.coerceAtLeast(1)
                                        val revealed = sweep * totalChars
                                        for (lineIndex in 0 until layout.lineCount) {
                                            val start = layout.getLineStart(lineIndex)
                                            val end = layout.getLineEnd(lineIndex, visibleEnd = true)
                                            if (end <= start) continue
                                            val lineFraction = (
                                                (revealed - start) / (end - start).toFloat()
                                                ).coerceIn(0f, 1f)
                                            if (lineFraction <= 0f) continue
                                            val left = layout.getLineLeft(lineIndex)
                                            val right = layout.getLineRight(lineIndex)
                                            clipRect(
                                                left = left,
                                                top = layout.getLineTop(lineIndex),
                                                right = left + (right - left) * lineFraction,
                                                bottom = layout.getLineBottom(lineIndex)
                                            ) {
                                                drawText(
                                                    layout,
                                                    color = MusicPalette.accentTeal,
                                                    topLeft = Offset.Zero
                                                )
                                            }
                                        }
                                    }
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
private fun MusicActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    active -> MusicPalette.accentSoft
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                when {
                    focused -> Color.White
                    active -> MusicPalette.accent
                    else -> MusicPalette.border
                },
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                focused -> MusicPalette.background
                active -> MusicPalette.accent
                else -> MusicPalette.textSecondary
            },
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = when {
                focused -> MusicPalette.background
                active -> MusicPalette.accent
                else -> MusicPalette.textSecondary
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun PlaylistPickerOverlay(
    playlists: List<StoredPlaylist>,
    firstFocus: FocusRequester,
    onDismiss: () -> Unit,
    onPick: (StoredPlaylist) -> Unit,
    onCreate: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MusicPalette.surface)
                .padding(24.dp)
        ) {
            Text(
                text = "Add to playlist",
                color = MusicPalette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "new-playlist") {
                    PickerRow(
                        icon = Icons.Default.Add,
                        label = "New playlist",
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = onCreate
                    )
                }
                itemsIndexed(
                    items = playlists,
                    key = { _, playlist -> playlist.id }
                ) { _, playlist ->
                    PickerRow(
                        icon = Icons.Default.PlaylistPlay,
                        label = playlist.name,
                        onClick = { onPick(playlist) }
                    )
                }
                item(key = "cancel") {
                    PickerRow(
                        icon = Icons.Default.Close,
                        label = "Cancel",
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MusicPalette.surfaceFocused else Color.Transparent)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color.White else MusicPalette.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = MusicPalette.text,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UpNextRow(
    song: com.music.bitchord.data.model.Song,
    index: Int,
    modifier: Modifier = Modifier,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(9.dp))
            .background(if (focused) MusicPalette.accent else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(9.dp)
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown &&
                    onDown != null
                ) {
                    if (onDown.invoke()) true else false
                } else false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = (index + 1).toString(),
            color = MusicPalette.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.width(18.dp)
        )
        MusicArtwork(
            url = song.thumbnailUrl,
            modifier = Modifier.size(40.dp),
            cornerRadius = 6.dp,
            iconSize = 16.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                color = MusicPalette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        song.durationText?.let {
            Text(text = it, color = MusicPalette.textMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (focused) Color(0x44FFFFFF) else Color.Transparent)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}


