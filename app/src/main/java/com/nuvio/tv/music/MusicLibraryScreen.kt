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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.Song

private enum class LibraryFilter(val label: String) {
    RECENTS("Recently played"),
    LIKED("Liked Songs"),
    PLAYLISTS("Playlists"),
    DOWNLOADS("Downloads")
}

@Composable
internal fun MusicLibraryScreen(
    liked: List<Song>,
    recents: List<Song>,
    playlists: List<StoredPlaylist>,
    accountName: String?,
    accountLiked: List<Song>,
    accountPlaylists: List<MusicAccountPlaylist>,
    accountLoading: Boolean,
    onRefreshAccount: () -> Unit,
    onSignIn: () -> Unit,
    onManageAccount: () -> Unit,
    onPlayAccountLiked: (Int) -> Unit,
    onOpenAccountPlaylist: (MusicAccountPlaylist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlayLiked: (Int) -> Unit,
    onPlayRecents: (Int) -> Unit,
    onOpenPlaylist: (StoredPlaylist) -> Unit,
    onOpenDownloads: () -> Unit,
    onPlayDownloaded: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val playback by MusicPlayer.state.collectAsState()
    val downloads by MusicDownloads.state.collectAsState()
    var filter by remember { mutableStateOf(LibraryFilter.RECENTS) }
    val firstFilterFocus = remember { FocusRequester() }
    val contentState = rememberLazyListState()

    val downloadedSongs = remember(downloads.completed) { downloads.completed.map { it.song } }
    val likedCount = liked.size + accountLiked.size
    val playlistCount = playlists.size + accountPlaylists.size

    LaunchedEffect(filter) {
        runCatching { contentState.animateScrollToItem(0) }
    }

    Row(
        modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            Text(
                text = "Library",
                color = MusicPalette.text,
                fontFamily = MusicDisplay,
                fontSize = 40.sp,
                lineHeight = 43.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your likes, playlists and listening history.",
                color = MusicPalette.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))

            LibraryRailAccount(
                accountName = accountName,
                onSignIn = onSignIn,
                onManage = onManageAccount
            )
            if (accountName != null) {
                Spacer(Modifier.height(8.dp))
                LibraryActionButton(
                    icon = Icons.Default.Refresh,
                    label = if (accountLoading) "Refreshing..." else "Refresh YouTube Music",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefreshAccount
                )
            }
            Spacer(Modifier.height(14.dp))

            LibraryFilterRow(
                icon = Icons.Default.Schedule,
                label = LibraryFilter.RECENTS.label,
                count = recents.size,
                selected = filter == LibraryFilter.RECENTS,
                modifier = Modifier.focusRequester(firstFilterFocus),
                onSelect = { filter = LibraryFilter.RECENTS }
            )
            Spacer(Modifier.height(6.dp))
            LibraryFilterRow(
                icon = Icons.Default.Favorite,
                label = LibraryFilter.LIKED.label,
                count = likedCount,
                selected = filter == LibraryFilter.LIKED,
                onSelect = { filter = LibraryFilter.LIKED }
            )
            Spacer(Modifier.height(6.dp))
            LibraryFilterRow(
                icon = Icons.Default.PlaylistPlay,
                label = LibraryFilter.PLAYLISTS.label,
                count = playlistCount,
                selected = filter == LibraryFilter.PLAYLISTS,
                onSelect = { filter = LibraryFilter.PLAYLISTS }
            )
            Spacer(Modifier.height(6.dp))
            LibraryFilterRow(
                icon = Icons.Default.Download,
                label = LibraryFilter.DOWNLOADS.label,
                count = downloads.completed.size,
                selected = filter == LibraryFilter.DOWNLOADS,
                onSelect = { filter = LibraryFilter.DOWNLOADS }
            )

            Spacer(Modifier.weight(1f))

            RailNewPlaylistButton(onClick = onCreatePlaylist)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (downloads.totalBytes > 0L) {
                    "${formatDownloadBytes(downloads.totalBytes)} on this device"
                } else {
                    "Nothing downloaded yet"
                },
                color = MusicPalette.textMuted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            state = contentState,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "library-hero") {
                val hero = when (filter) {
                    LibraryFilter.RECENTS -> {
                        val canPlay = recents.isNotEmpty()
                        LibraryHeroModel(
                            badge = "YOUR HISTORY",
                            title = "Jump back in",
                            subtitle = if (canPlay) {
                                "${recents.size} tracks · pick up where you left off"
                            } else {
                                "Tracks you play will appear here."
                            },
                            artworkUrl = recents.firstOrNull()?.thumbnailUrl,
                            primaryLabel = "Play",
                            onPrimary = if (canPlay) ({ onPlayRecents(0) }) else null,
                            secondaryLabel = if (recents.size > 1) "Shuffle" else null,
                            onSecondary = if (recents.size > 1) {
                                ({ onPlayRecents(recents.indices.random()) })
                            } else {
                                null
                            }
                        )
                    }
                    LibraryFilter.LIKED -> {
                        val firstPlay: (() -> Unit)? = when {
                            accountLiked.isNotEmpty() -> ({ onPlayAccountLiked(0) })
                            liked.isNotEmpty() -> ({ onPlayLiked(0) })
                            else -> null
                        }
                        LibraryHeroModel(
                            badge = "LIKED SONGS",
                            title = "Songs you love",
                            subtitle = if (likedCount > 0) {
                                "$likedCount tracks · the ones you keep coming back to"
                            } else {
                                "Songs you like will appear here."
                            },
                            artworkUrl = (accountLiked.firstOrNull() ?: liked.firstOrNull())?.thumbnailUrl,
                            primaryLabel = "Play",
                            onPrimary = firstPlay
                        )
                    }
                    LibraryFilter.PLAYLISTS -> {
                        val firstLocal = playlists.firstOrNull()
                        LibraryHeroModel(
                            badge = "COLLECTION",
                            title = "Your collection",
                            subtitle = if (playlistCount > 0) {
                                "$playlistCount playlists · on this device and YouTube Music"
                            } else {
                                "Create a playlist, then add songs from Now Playing."
                            },
                            artworkUrl = accountPlaylists.firstOrNull()?.thumbnailUrl
                                ?: playlists.firstOrNull()?.songs?.firstOrNull()?.thumbnailUrl,
                            primaryLabel = "New playlist",
                            onPrimary = onCreatePlaylist,
                            secondaryLabel = if (firstLocal != null) "Open first" else null,
                            onSecondary = if (firstLocal != null) {
                                ({ onOpenPlaylist(firstLocal) })
                            } else {
                                null
                            }
                        )
                    }
                    LibraryFilter.DOWNLOADS -> {
                        LibraryHeroModel(
                            badge = "OFFLINE",
                            title = "Offline library",
                            subtitle = if (downloadedSongs.isEmpty()) {
                                "Save albums and playlists for offline listening."
                            } else {
                                "${downloadedSongs.size} tracks · " +
                                    "${formatDownloadBytes(downloads.totalBytes)} · plays offline"
                            },
                            artworkUrl = downloadedSongs.firstOrNull()?.thumbnailUrl,
                            primaryLabel = "Open downloads",
                            onPrimary = onOpenDownloads,
                            secondaryLabel = if (downloadedSongs.isNotEmpty()) "Play all" else null,
                            onSecondary = if (downloadedSongs.isNotEmpty()) {
                                ({ onPlayDownloaded(downloadedSongs, 0) })
                            } else {
                                null
                            }
                        )
                    }
                }
                LibraryHero(hero)
            }

            when (filter) {
                LibraryFilter.RECENTS -> {
                    item(key = "recents-header") {
                        LibrarySectionHeader(
                            icon = Icons.Default.Schedule,
                            title = "Recently played",
                            count = recents.size,
                            horizontalPadding = 0.dp,
                            onPlayAll = if (recents.isNotEmpty()) {
                                { onPlayRecents(0) }
                            } else {
                                null
                            }
                        )
                    }
                    if (recents.isEmpty()) {
                        item(key = "recents-empty") {
                            LibraryHint(
                                icon = Icons.Default.Schedule,
                                text = "Tracks you play will appear here.",
                                horizontalPadding = 0.dp
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = recents,
                            key = { index, song -> "recent:${song.videoId}#$index" }
                        ) { index, song ->
                            MusicTrackRow(
                                song = song,
                                index = index,
                                isCurrent = playback.song?.videoId == song.videoId,
                                isPlaying = playback.isPlaying &&
                                    playback.song?.videoId == song.videoId,
                                onClick = { onPlayRecents(index) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                LibraryFilter.LIKED -> {
                    if (accountName != null) {
                        item(key = "account-liked-header") {
                            LibrarySectionHeader(
                                icon = Icons.Default.Favorite,
                                title = "Liked on YouTube Music",
                                count = accountLiked.size,
                                horizontalPadding = 0.dp,
                                onPlayAll = if (accountLiked.isNotEmpty()) {
                                    { onPlayAccountLiked(0) }
                                } else {
                                    null
                                }
                            )
                        }
                        if (accountLoading && accountLiked.isEmpty()) {
                            item(key = "account-liked-loading") {
                                LibraryHint(
                                    icon = Icons.Default.AccountCircle,
                                    text = "Loading your YouTube Music likes…",
                                    horizontalPadding = 0.dp
                                )
                            }
                        } else if (accountLiked.isEmpty()) {
                            item(key = "account-liked-empty") {
                                LibraryHint(
                                    icon = Icons.Default.Favorite,
                                    text = "Songs you like on YouTube Music will appear here.",
                                    horizontalPadding = 0.dp
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = accountLiked,
                                key = { index, song -> "account-liked:${song.videoId}#$index" }
                            ) { index, song ->
                                MusicTrackRow(
                                    song = song,
                                    index = index,
                                    isCurrent = playback.song?.videoId == song.videoId,
                                    isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                                    onClick = { onPlayAccountLiked(index) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    item(key = "local-liked-header") {
                        LibrarySectionHeader(
                            icon = Icons.Default.Favorite,
                            title = if (accountName != null) "Liked on this device" else "Liked Songs",
                            count = liked.size,
                            horizontalPadding = 0.dp,
                            onPlayAll = if (liked.isNotEmpty()) {
                                { onPlayLiked(0) }
                            } else {
                                null
                            }
                        )
                    }
                    if (liked.isEmpty()) {
                        item(key = "local-liked-empty") {
                            LibraryHint(
                                icon = Icons.Default.Favorite,
                                text = "Songs you like will appear here. Use the heart on Now Playing.",
                                horizontalPadding = 0.dp
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = liked,
                            key = { index, song -> "liked:${song.videoId}#$index" }
                        ) { index, song ->
                            MusicTrackRow(
                                song = song,
                                index = index,
                                isCurrent = playback.song?.videoId == song.videoId,
                                isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                                onClick = { onPlayLiked(index) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                LibraryFilter.PLAYLISTS -> {
                    if (accountName != null) {
                        item(key = "account-playlists-header") {
                            LibrarySectionHeader(
                                icon = Icons.Default.PlaylistPlay,
                                title = "From YouTube Music",
                                count = accountPlaylists.size,
                                horizontalPadding = 0.dp
                            )
                        }
                        if (accountPlaylists.isEmpty()) {
                            item(key = "account-playlists-empty") {
                                LibraryHint(
                                    icon = Icons.Default.PlaylistPlay,
                                    text = "Playlists from your YouTube Music account will appear here.",
                                    horizontalPadding = 0.dp
                                )
                            }
                        } else {
                            item(key = "account-playlists-grid") {
                                LibraryTileGrid(
                                    items = accountPlaylists.map { playlist ->
                                        LibraryTile(
                                            artworkUrl = playlist.thumbnailUrl,
                                            title = playlist.title,
                                            subtitle = playlist.count?.let {
                                                "$it track" + if (it == 1) "" else "s"
                                            } ?: "YouTube Music",
                                            onClick = { onOpenAccountPlaylist(playlist) }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    item(key = "local-playlists-header") {
                        LibrarySectionHeader(
                            icon = Icons.Default.PlaylistPlay,
                            title = if (accountName != null) "On this device" else "Playlists",
                            count = playlists.size,
                            horizontalPadding = 0.dp
                        )
                    }
                    item(key = "local-playlists-grid") {
                        LibraryTileGrid(
                            items = playlists.map { playlist ->
                                LibraryTile(
                                    artworkUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
                                    title = playlist.name,
                                    subtitle = "${playlist.songs.size} track" +
                                        if (playlist.songs.size == 1) "" else "s",
                                    onClick = { onOpenPlaylist(playlist) }
                                )
                            } + LibraryTile(
                                artworkUrl = null,
                                title = "New playlist",
                                subtitle = "Create a playlist",
                                isNew = true,
                                onClick = onCreatePlaylist
                            )
                        )
                    }
                }

                LibraryFilter.DOWNLOADS -> {
                    item(key = "downloads-card") {
                        DownloadsLibraryRow(
                            count = downloads.completed.size,
                            bytes = downloads.totalBytes,
                            active = downloads.active.size + downloads.starting.size,
                            onClick = onOpenDownloads,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item(key = "downloads-hint") {
                        LibraryHint(
                            icon = Icons.Default.Download,
                            text = "Manage downloads, retry failures and free space in the Downloads screen.",
                            horizontalPadding = 0.dp
                        )
                    }
                    if (downloadedSongs.isNotEmpty()) {
                        item(key = "downloaded-tracks-header") {
                            LibrarySectionHeader(
                                icon = Icons.Default.Download,
                                title = "Downloaded tracks",
                                count = downloadedSongs.size,
                                horizontalPadding = 0.dp,
                                onPlayAll = { onPlayDownloaded(downloadedSongs, 0) }
                            )
                        }
                        item(key = "downloaded-tracks-grid") {
                            LibraryTileGrid(
                                items = downloadedSongs.take(12).mapIndexed { index, song ->
                                    LibraryTile(
                                        artworkUrl = song.thumbnailUrl,
                                        title = song.title,
                                        subtitle = song.artist,
                                        onClick = { onPlayDownloaded(downloadedSongs, index) }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRailAccount(
    accountName: String?,
    onSignIn: () -> Unit,
    onManage: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(shape)
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(2.dp, if (focused) Color.White else MusicPalette.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { if (accountName == null) onSignIn() else onManage() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MusicPalette.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MusicPalette.accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "YouTube Music account",
                color = MusicPalette.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (accountName == null) "Sign in to YouTube Music" else accountName,
                color = MusicPalette.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (accountName == null) "Sign in" else "Manage",
            color = if (focused) MusicPalette.text else MusicPalette.accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LibraryFilterRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> MusicPalette.surfaceFocused
                    else -> MusicPalette.surface.copy(alpha = 0.55f)
                }
            )
            .border(
                2.dp,
                when {
                    focused -> MusicPalette.accent
                    selected -> Color.Transparent
                    else -> MusicPalette.border.copy(alpha = 0.55f)
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MusicPalette.background else MusicPalette.accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (selected) MusicPalette.background else MusicPalette.text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            color = if (selected) {
                MusicPalette.background.copy(alpha = 0.55f)
            } else {
                MusicPalette.textMuted
            },
            fontSize = 11.5.sp
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (selected) MusicPalette.background.copy(alpha = 0.55f) else MusicPalette.textMuted,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun RailNewPlaylistButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "New playlist",
            color = if (focused) MusicPalette.background else MusicPalette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class LibraryHeroModel(
    val badge: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val primaryLabel: String,
    val onPrimary: (() -> Unit)?,
    val secondaryLabel: String? = null,
    val onSecondary: (() -> Unit)? = null
)

@Composable
private fun LibraryHero(model: LibraryHeroModel) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MusicPalette.accent.copy(alpha = 0.28f),
                        MusicPalette.surface,
                        MusicPalette.background
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MusicArtwork(
            url = model.artworkUrl,
            modifier = Modifier.size(144.dp),
            cornerRadius = 14.dp,
            iconSize = 44.dp,
            artworkPx = 720
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = model.badge,
                color = MusicPalette.accentTeal,
                fontSize = 9.5.sp,
                letterSpacing = 1.7.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = model.title,
                color = MusicPalette.text,
                fontFamily = MusicDisplay,
                fontSize = 30.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = model.subtitle,
                color = MusicPalette.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (model.onPrimary != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    LibraryActionButton(
                        icon = Icons.Default.PlayArrow,
                        label = model.primaryLabel,
                        onClick = model.onPrimary
                    )
                    if (model.onSecondary != null && model.secondaryLabel != null) {
                        LibraryActionButton(
                            icon = Icons.Default.Shuffle,
                            label = model.secondaryLabel,
                            onClick = model.onSecondary
                        )
                    }
                }
            }
        }
    }
}

private data class LibraryTile(
    val artworkUrl: String?,
    val title: String,
    val subtitle: String,
    val isNew: Boolean = false,
    val onClick: () -> Unit
)

@Composable
private fun LibraryTileGrid(
    items: List<LibraryTile>,
    columns: Int = 4
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { item ->
                    LibraryTileCard(item, Modifier.weight(1f))
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LibraryTileCard(
    item: LibraryTile,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(140),
        label = "libraryTileScale"
    )
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) MusicPalette.surfaceFocused else Color.Transparent)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = item.onClick)
            .padding(6.dp)
    ) {
        if (item.isNew) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MusicPalette.surfaceRaised)
                    .border(1.dp, MusicPalette.border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MusicPalette.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        } else {
            MusicArtwork(
                url = item.artworkUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = 12.dp,
                iconSize = 28.dp,
                artworkPx = 480
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = MusicPalette.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.subtitle,
            color = MusicPalette.textSecondary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LibrarySectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int,
    onPlayAll: (() -> Unit)? = null,
    horizontalPadding: Dp = 26.dp
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MusicPalette.accent,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            color = MusicPalette.text,
            fontFamily = MusicDisplay,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = count.toString(),
            color = MusicPalette.textMuted,
            fontSize = 11.5.sp
        )
        Spacer(Modifier.weight(1f))
        if (onPlayAll != null) {
            LibraryActionButton(
                icon = Icons.Default.PlayArrow,
                label = "Play all",
                onClick = onPlayAll
            )
        }
    }
}

@Composable
private fun LibraryHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    horizontalPadding: Dp = 26.dp
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MusicPalette.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MusicPalette.textMuted,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            color = MusicPalette.textSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun AccountSignInCard(onSignIn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MusicPalette.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MusicPalette.accent,
            modifier = Modifier.size(30.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "YouTube Music account",
                color = MusicPalette.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Sign in to YouTube Music to see your liked songs and playlists.",
                color = MusicPalette.textSecondary,
                fontSize = 13.sp
            )
        }
        LibraryActionButton(
            icon = Icons.Default.AccountCircle,
            label = "Sign in",
            onClick = onSignIn
        )
    }
}

@Composable
private fun AccountHeader(accountName: String, onManage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MusicPalette.accent,
            modifier = Modifier.size(26.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "YouTube Music account",
                color = MusicPalette.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = accountName,
                color = MusicPalette.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LibraryActionButton(
            icon = Icons.Default.Person,
            label = "Manage",
            onClick = onManage
        )
    }
}

@Composable
private fun AccountPlaylistGrid(
    playlists: List<MusicAccountPlaylist>,
    onOpen: (MusicAccountPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        playlists.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { playlist ->
                    AccountPlaylistTile(
                        playlist = playlist,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(playlist) }
                    )
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AccountPlaylistTile(
    playlist: MusicAccountPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(140),
        label = "accountPlaylistScale"
    )
    val shape = RoundedCornerShape(12.dp)
    val subtitle = playlist.count?.let {
        "$it track" + if (it == 1) "" else "s"
    } ?: "Music playlist"
    Row(
        modifier
            .height(78.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MusicArtwork(
            url = playlist.thumbnailUrl,
            modifier = Modifier.size(48.dp),
            cornerRadius = 9.dp,
            iconSize = 22.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                color = MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MusicPalette.textSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DownloadsLibraryRow(
    count: Int,
    bytes: Long,
    active: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) MusicPalette.surfaceFocused else MusicPalette.surface
            )
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MusicPalette.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = MusicPalette.accent,
                modifier = Modifier.size(23.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "Downloads",
                color = MusicPalette.text,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    active > 0 -> "$active downloading · $count on this device"
                    count > 0 -> "$count track" + (if (count == 1) "" else "s") +
                        " · ${formatDownloadBytes(bytes)} · plays offline"
                    else -> "Save albums and playlists for offline listening"
                },
                color = if (count > 0) MusicPalette.accentTeal else MusicPalette.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open downloads",
            tint = if (focused) MusicPalette.text else MusicPalette.textMuted,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun LibraryActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (focused) MusicPalette.background else MusicPalette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LocalPlaylistGrid(
    playlists: List<StoredPlaylist>,
    onOpen: (StoredPlaylist) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        playlists.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { playlist ->
                    LocalPlaylistTile(
                        playlist = playlist,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(playlist) }
                    )
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LocalPlaylistTile(
    playlist: StoredPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(140),
        label = "localPlaylistScale"
    )
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(78.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MusicArtwork(
            url = playlist.songs.firstOrNull()?.thumbnailUrl,
            modifier = Modifier.size(48.dp),
            cornerRadius = 9.dp,
            iconSize = 22.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${playlist.songs.size} track" +
                    if (playlist.songs.size == 1) "" else "s",
                color = MusicPalette.textSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun MusicLocalPlaylistScreen(
    playlist: StoredPlaylist,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playback by MusicPlayer.state.collectAsState()
    val songs = remember(playlist.songs) { playlist.songs.map { it.toSong() } }
    val firstFocus = remember { FocusRequester() }
    var backFocused by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.id) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "local-playlist-header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
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
                        text = "PLAYLIST",
                        color = MusicPalette.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = playlist.name,
                    color = MusicPalette.text,
                    fontFamily = MusicDisplay,
                    fontSize = 40.sp,
                    lineHeight = 45.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${songs.size} track" + if (songs.size == 1) "" else "s",
                    color = MusicPalette.textMuted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LibraryActionButton(
                        icon = Icons.Default.PlayArrow,
                        label = "Play",
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = { if (songs.isNotEmpty()) onPlay(0) }
                    )
                    LibraryActionButton(
                        icon = Icons.Default.Delete,
                        label = "Delete playlist",
                        onClick = onDelete
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (songs.isEmpty()) {
            item(key = "local-playlist-empty") {
                MusicEmptyState(
                    title = "No songs yet",
                    message = "Add songs from Now Playing → Playlist.",
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }
        } else {
            itemsIndexed(
                items = songs,
                key = { index, song -> "${song.videoId}#$index" }
            ) { index, song ->
                MusicTrackRow(
                    song = song,
                    index = index,
                    isCurrent = playback.song?.videoId == song.videoId,
                    isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                    onClick = { onPlay(index) },
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
            }
        }
    }
}
