package com.nuvio.tv.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import kotlin.math.roundToInt

@Composable
internal fun MusicDetailScreen(
    state: MusicDetailState,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onRetry: () -> Unit,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playback by MusicPlayer.state.collectAsState()
    val downloads by MusicDownloads.state.collectAsState()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.songs.size) {
        if (nearEnd) onLoadMore()
    }
    val playFocus = remember { FocusRequester() }
    val firstTrackFocus = remember { FocusRequester() }
    val downloadable = state.songs.filter { it.videoId.isNotBlank() }
    val downloadedCount = downloadable.count { song ->
        downloads.entry(song.videoId)?.status == MusicDownloadStatus.COMPLETE
    }
    val downloadActive = downloadable.any { song ->
        downloads.starting.containsKey(song.videoId) ||
            downloads.entry(song.videoId)?.status == MusicDownloadStatus.DOWNLOADING ||
            downloads.entry(song.videoId)?.status == MusicDownloadStatus.QUEUED ||
            downloads.entry(song.videoId)?.status == MusicDownloadStatus.PAUSED
    }
    val allDownloaded = downloadable.isNotEmpty() &&
        downloadedCount == downloadable.size
    val downloadProgress = if (downloadable.isEmpty()) {
        0f
    } else {
        downloadable.sumOf { song ->
            val entry = downloads.entry(song.videoId)
            when (entry?.status) {
                MusicDownloadStatus.COMPLETE -> 1.0
                MusicDownloadStatus.DOWNLOADING,
                MusicDownloadStatus.PAUSED -> entry.progress.toDouble()
                else -> 0.0
            }
        }.div(downloadable.size).toFloat()
    }
    LaunchedEffect(state.browseId, state.loading) {
        if (!state.loading && state.songs.isNotEmpty()) {
            runCatching { playFocus.requestFocus() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "detail-header") {
            MusicDetailHeader(
                state = state,
                playFocus = playFocus,
                firstTrackFocus = firstTrackFocus,
                downloadedCount = downloadedCount,
                downloadActive = downloadActive,
                downloadProgress = downloadProgress,
                allDownloaded = allDownloaded,
                onBack = onBack,
                onPlay = { onPlay(0) },
                onDownload = {
                    if (!allDownloaded) {
                        MusicDownloads.downloadAll(downloadable.filter { song ->
                            downloads.entry(song.videoId)?.status !=
                                MusicDownloadStatus.COMPLETE
                        })
                    }
                }
            )
        }
        if (state.type == BrowseType.ARTIST &&
            !state.loading && state.error == null && state.songs.isNotEmpty()
        ) {
            item(key = "detail-popular") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Popular",
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.songs.size.toString(),
                        color = MusicPalette.textMuted,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
        when {
            state.loading -> item(key = "detail-loading") {
                MusicLoading(Modifier.fillMaxWidth().height(240.dp))
            }
            state.error != null -> item(key = "detail-error") {
                MusicErrorState(
                    message = state.error,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                )
            }
            state.songs.isEmpty() && state.sections.isEmpty() -> item(key = "detail-empty") {
                MusicEmptyState(
                    title = "Nothing to play",
                    message = "This page didn't list any tracks.",
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                )
            }
            state.songs.isEmpty() -> Unit
            else -> {
                itemsIndexed(
                    items = state.songs,
                    key = { index, song -> "${song.videoId}#$index" }
                ) { index, song ->
                    MusicTrackRow(
                        song = song,
                        index = index,
                        isCurrent = playback.song?.videoId == song.videoId,
                        isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                        onClick = { onPlay(index) },
                        modifier = Modifier
                            .padding(horizontal = 26.dp)
                            .then(
                                if (index == 0) Modifier.focusRequester(firstTrackFocus)
                                else Modifier
                            )
                    )
                }
                if (state.loadingMore) {
                    item(key = "detail-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MusicPalette.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
        // An artist can have no top songs but still list releases and playlists.
        if (!state.loading && state.error == null && state.sections.isNotEmpty()) {
            item(key = "detail-sections") {
                Spacer(Modifier.height(18.dp))
            }
            itemsIndexed(
                items = state.sections,
                key = { index, shelf -> "s:${shelf.title}#$index" }
            ) { _, shelf ->
                MusicShelfRow(
                    shelf = shelf,
                    onItemClick = { _, index -> onShelfItemClick(shelf, index) },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MusicDetailHeader(
    state: MusicDetailState,
    playFocus: FocusRequester,
    firstTrackFocus: FocusRequester,
    downloadedCount: Int,
    downloadActive: Boolean,
    downloadProgress: Float,
    allDownloaded: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    var backFocused by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }
    var downloadFocused by remember { mutableStateOf(false) }
    val animatedDownloadProgress by animateFloatAsState(
        targetValue = downloadProgress.coerceIn(0f, 1f),
        animationSpec = tween(360),
        label = "musicDetailDownloadProgress"
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (backFocused) Color.White else MusicPalette.surfaceRaised)
                    .border(
                        2.dp,
                        if (backFocused) Color.White else MusicPalette.border,
                        RoundedCornerShape(50)
                    )
                    .onFocusChanged { backFocused = it.isFocused }
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (backFocused) MusicPalette.background else MusicPalette.text,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(
                text = when (state.type) {
                    BrowseType.ALBUM -> "Album"
                    BrowseType.ARTIST -> "Artist"
                    BrowseType.PLAYLIST -> "Playlist"
                    BrowseType.OTHER -> "Music"
                }.uppercase(),
                color = MusicPalette.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            MusicArtwork(
                url = state.thumbnailUrl,
                modifier = Modifier.size(236.dp),
                cornerRadius = if (state.type == BrowseType.ARTIST) 118.dp else 16.dp,
                iconSize = 54.dp,
                artworkPx = 1080
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    color = MusicPalette.text,
                    fontFamily = MusicDisplay,
                    fontSize = 40.sp,
                    lineHeight = 45.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val detailSubtitle = state.subtitle.withoutMusicServiceBrand()
                if (detailSubtitle.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detailSubtitle,
                        color = MusicPalette.textSecondary,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.songs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${state.songs.size} track" + if (state.songs.size == 1) "" else "s",
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
                if ((state.type == BrowseType.ARTIST || state.type == BrowseType.ALBUM) &&
                    state.description.isNotBlank()
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.description,
                        color = MusicPalette.textSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (playFocused) MusicPalette.text else MusicPalette.accent
                            )
                            .border(
                                2.dp,
                                if (playFocused) MusicPalette.text else Color.Transparent,
                                RoundedCornerShape(50)
                            )
                            .focusRequester(playFocus)
                            .onFocusChanged { playFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionDown
                                ) {
                                    runCatching { firstTrackFocus.requestFocus() }.isSuccess
                                } else false
                            }
                            .clickable(onClick = onPlay)
                            .padding(horizontal = 24.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (playFocused) MusicPalette.background else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Play",
                            color = if (playFocused) MusicPalette.background else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    allDownloaded -> MusicPalette.accentSoft
                                    downloadFocused -> MusicPalette.text
                                    else -> MusicPalette.surfaceRaised
                                }
                            )
                            .border(
                                2.dp,
                                when {
                                    downloadFocused -> MusicPalette.text
                                    allDownloaded -> MusicPalette.accent.copy(alpha = 0.55f)
                                    else -> MusicPalette.border
                                },
                                RoundedCornerShape(50)
                            )
                            .onFocusChanged { downloadFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionDown
                                ) {
                                    runCatching { firstTrackFocus.requestFocus() }.isSuccess
                                } else false
                            }
                            .clickable(onClick = onDownload)
                    ) {
                        if (downloadActive || allDownloaded) {
                            Box(Modifier.matchParentSize().clip(RoundedCornerShape(50))) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(animatedDownloadProgress)
                                        .background(
                                            when {
                                                allDownloaded ->
                                                    MusicPalette.accent.copy(alpha = 0.30f)
                                                downloadFocused ->
                                                    MusicPalette.accent.copy(alpha = 0.55f)
                                                else ->
                                                    MusicPalette.accent.copy(alpha = 0.35f)
                                            }
                                        )
                                )
                            }
                        }
                        Row(
                            Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (allDownloaded) Icons.Default.DownloadDone
                                else Icons.Default.Download,
                                contentDescription = null,
                                tint = when {
                                    downloadFocused -> MusicPalette.background
                                    allDownloaded -> MusicPalette.accent
                                    else -> MusicPalette.text
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = when {
                                    allDownloaded -> "Downloaded"
                                    downloadActive -> {
                                        val percent = (downloadProgress * 100).roundToInt()
                                        if (percent > 0) {
                                            "Downloading $percent%"
                                        } else {
                                            "Preparing…"
                                        }
                                    }
                                    downloadedCount > 0 -> "Download rest"
                                    else -> "Download all"
                                },
                                color = when {
                                    downloadFocused -> MusicPalette.background
                                    allDownloaded -> MusicPalette.accent
                                    else -> MusicPalette.text
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
