package com.nuvio.tv.music

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NavidromeSection { ALBUMS, PLAYLISTS }

/**
 * Navidrome / Subsonic browser. Linking supports both a phone QR page and the TV's own
 * on-screen form, because remotes are a bad place to type a server URL.
 */
@Composable
internal fun MusicNavidromeScreen(
    onClose: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    val config by MusicNavidrome.config.collectAsState()
    val setup by MusicNavidromeSetup.state.collectAsState()
    val playback by MusicPlayer.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showServerSetup by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(NavidromeSection.ALBUMS) }
    var albums by remember { mutableStateOf<List<NavidromeAlbum>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<NavidromePlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var albumDetail by remember { mutableStateOf<NavidromeAlbumDetail?>(null) }
    var playlistDetail by remember { mutableStateOf<NavidromePlaylistDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        MusicNavidrome.init(context)
        if (!MusicNavidrome.config.value.configured) {
            MusicNavidromeSetup.start(context)
        }
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    LaunchedEffect(config.configured) {
        if (config.configured) {
            MusicNavidromeSetup.stop()
            showServerSetup = false
        } else {
            showServerSetup = true
            MusicNavidromeSetup.start(context)
        }
    }

    LaunchedEffect(config.configured, reload) {
        if (!config.configured) return@LaunchedEffect
        loading = true
        error = null
        val albumsResult = withContext(Dispatchers.IO) { MusicNavidrome.albums() }
        val playlistsResult = withContext(Dispatchers.IO) { MusicNavidrome.playlists() }
        albumsResult.onSuccess { albums = it }
        playlistsResult.onSuccess { playlists = it }
        error = albumsResult.exceptionOrNull()?.message
            ?: playlistsResult.exceptionOrNull()?.message
        loading = false
    }

    LaunchedEffect(albumDetail?.album?.id) {
        val album = albumDetail?.album ?: return@LaunchedEffect
        if (albumDetail?.songs?.isNotEmpty() == true) return@LaunchedEffect
        detailLoading = true
        detailError = null
        withContext(Dispatchers.IO) { MusicNavidrome.album(album.id) }.fold(
            onSuccess = { albumDetail = it },
            onFailure = { detailError = it.message ?: "Couldn't load that album." }
        )
        detailLoading = false
    }

    LaunchedEffect(playlistDetail?.playlist?.id) {
        val playlist = playlistDetail?.playlist ?: return@LaunchedEffect
        if (playlistDetail?.songs?.isNotEmpty() == true) return@LaunchedEffect
        detailLoading = true
        detailError = null
        withContext(Dispatchers.IO) { MusicNavidrome.playlist(playlist.id) }.fold(
            onSuccess = { playlistDetail = it },
            onFailure = { detailError = it.message ?: "Couldn't load that playlist." }
        )
        detailLoading = false
    }

    BackHandler {
        when {
            albumDetail != null -> albumDetail = null
            playlistDetail != null -> playlistDetail = null
            showServerSetup && config.configured -> showServerSetup = false
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                NavidromeCloseButton(
                    modifier = Modifier.focusRequester(closeFocus),
                    onClick = {
                        if (albumDetail != null) albumDetail = null
                        else if (playlistDetail != null) playlistDetail = null
                        else if (showServerSetup && config.configured) showServerSetup = false
                        else onClose()
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_integrations_navidrome),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            !config.configured -> stringResource(R.string.music_navidrome_link_subsonic)
                            config.configured -> config.url
                            else -> stringResource(R.string.music_navidrome_link_server)
                        },
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (config.configured && albumDetail == null && playlistDetail == null) {
                    NavidromePill(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.music_refresh),
                        onClick = { reload++ }
                    )
                    NavidromePill(
                        icon = Icons.Default.Settings,
                        label = stringResource(R.string.music_server),
                        onClick = { showServerSetup = true }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                showServerSetup || !config.configured -> NavidromeSetupContent(
                    setup = setup,
                    config = config,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onStartSetup = {
                        MusicNavidromeSetup.stop()
                        MusicNavidromeSetup.start(context)
                    },
                    onCancel = if (config.configured) {
                        { showServerSetup = false }
                    } else {
                        null
                    },
                    onConnect = { url, user, password, done, failed ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                MusicNavidrome.connect(context, url, user, password)
                            }
                            result.fold(
                                onSuccess = {
                                    MusicNavidromeSetup.stop()
                                    showServerSetup = false
                                    done()
                                },
                                onFailure = { failed(it.message ?: "Couldn't connect.") }
                            )
                        }
                    }
                )

                albumDetail != null -> AlbumDetailContent(
                    detail = albumDetail!!,
                    loading = detailLoading,
                    error = detailError,
                    playback = playback,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onBack = { albumDetail = null },
                    onPlay = onPlay
                )

                playlistDetail != null -> PlaylistDetailContent(
                    detail = playlistDetail!!,
                    loading = detailLoading,
                    error = detailError,
                    playback = playback,
                    savedMessage = savedMessage,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onBack = { playlistDetail = null },
                    onPlay = onPlay,
                    onSave = {
                        val detail = playlistDetail
                        if (detail != null && detail.songs.isNotEmpty()) {
                            MusicLibrary.importPlaylist(detail.playlist.name, detail.songs)
                            savedMessage = "Saved “${detail.playlist.name}” to Library."
                        }
                    }
                )

                else -> MusicLibraryContent(
                    section = section,
                    onSection = { section = it },
                    albums = albums,
                    playlists = playlists,
                    loading = loading,
                    error = error,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRetry = { reload++ },
                    onAlbum = { albumDetail = NavidromeAlbumDetail(it, emptyList()) },
                    onPlaylist = { playlist ->
                        savedMessage = null
                        playlistDetail = NavidromePlaylistDetail(playlist, emptyList())
                    }
                )
            }
        }
    }
}

@Composable
private fun NavidromeSetupContent(
    setup: NavidromeSetupState,
    config: NavidromeConfig,
    modifier: Modifier = Modifier,
    onStartSetup: () -> Unit,
    onCancel: (() -> Unit)?,
    onConnect: (String, String, String, () -> Unit, (String) -> Unit) -> Unit
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        NavidromePhoneCard(
            setup = setup,
            modifier = Modifier.weight(1f),
            onStartSetup = onStartSetup
        )
        NavidromeManualCard(
            config = config,
            modifier = Modifier.weight(1f),
            onCancel = onCancel,
            onConnect = onConnect
        )
    }
}

@Composable
private fun NavidromePhoneCard(
    setup: NavidromeSetupState,
    modifier: Modifier = Modifier,
    onStartSetup: () -> Unit
) {
    val bitmap = rememberNavidromeQr(setup.url)
    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(MusicPalette.surface)
            .border(1.dp, MusicPalette.border, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.QrCode2,
                contentDescription = null,
                tint = MusicPalette.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.music_link_with_phone),
                color = MusicPalette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.music_navidrome_scan_help),
            color = MusicPalette.textSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .size(230.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.music_navidrome_qr),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = MusicPalette.background,
                    modifier = Modifier.size(86.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = setup.url ?: setup.error ?: stringResource(R.string.music_phone_starting),
            color = if (setup.error != null) MusicPalette.error else MusicPalette.textMuted,
            fontSize = 9.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        setup.message?.let {
            Spacer(Modifier.height(6.dp))
            Text(text = it, color = MusicPalette.accentTeal, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (setup.error != null || setup.url == null || setup.connected) {
            NavidromePill(
                icon = Icons.Default.CloudDone,
                label = if (setup.connected) stringResource(R.string.tracking_status_connected)
                else stringResource(R.string.music_show_new_qr),
                primary = true,
                onClick = onStartSetup
            )
        } else {
            Text(
                text = stringResource(R.string.music_waiting_for_phone),
                color = MusicPalette.textMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NavidromeManualCard(
    config: NavidromeConfig,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)?,
    onConnect: (String, String, String, () -> Unit, (String) -> Unit) -> Unit
) {
    var url by remember(config.url) { mutableStateOf(config.url) }
    var username by remember(config.username) { mutableStateOf(config.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(MusicPalette.surface)
            .border(1.dp, MusicPalette.border, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = MusicPalette.accentTeal,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.music_or_type_here),
                color = MusicPalette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.music_navidrome_remote_help),
            color = MusicPalette.textSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))
        NavidromeField(
            label = stringResource(R.string.music_server_url),
            value = url,
            placeholder = "https://music.example.com",
            onValueChange = { url = it }
        )
        Spacer(Modifier.height(10.dp))
        NavidromeField(
            label = stringResource(R.string.music_integrations_username),
            value = username,
            placeholder = stringResource(R.string.music_navidrome_user_placeholder),
            onValueChange = { username = it }
        )
        Spacer(Modifier.height(10.dp))
        NavidromeField(
            label = stringResource(R.string.music_integrations_password),
            value = password,
            placeholder = stringResource(R.string.music_navidrome_password_placeholder),
            onValueChange = { password = it },
            password = true
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MusicPalette.error, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            NavidromePill(
                icon = Icons.Default.CloudDone,
                label = if (busy) stringResource(R.string.music_connecting) else stringResource(R.string.music_webdav_connect_button),
                primary = true,
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    onConnect(url, username, password, {
                        busy = false
                    }, {
                        busy = false
                        error = it
                    })
                }
            )
            if (onCancel != null) {
                NavidromePill(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.action_cancel),
                    onClick = onCancel
                )
            }
        }
    }
}

@Composable
private fun MusicLibraryContent(
    section: NavidromeSection,
    onSection: (NavidromeSection) -> Unit,
    albums: List<NavidromeAlbum>,
    playlists: List<NavidromePlaylist>,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onAlbum: (NavidromeAlbum) -> Unit,
    onPlaylist: (NavidromePlaylist) -> Unit
) {
    Column(modifier) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        NavidromeSectionPill(
            icon = Icons.Default.Album,
            label = stringResource(R.string.music_navidrome_albums),
            selected = section == NavidromeSection.ALBUMS,
            count = albums.size,
            onClick = { onSection(NavidromeSection.ALBUMS) }
        )
        NavidromeSectionPill(
            icon = Icons.Default.PlaylistPlay,
            label = stringResource(R.string.music_navidrome_playlists),
            selected = section == NavidromeSection.PLAYLISTS,
            count = playlists.size,
            onClick = { onSection(NavidromeSection.PLAYLISTS) }
        )
    }
    Spacer(Modifier.height(12.dp))
    when {
        loading -> MusicLoading(Modifier.fillMaxWidth().height(240.dp))
        error != null -> MusicErrorState(
            message = error,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )
        section == NavidromeSection.ALBUMS && albums.isEmpty() -> NavidromeEmpty(
            stringResource(R.string.music_navidrome_no_albums)
        )
        section == NavidromeSection.PLAYLISTS && playlists.isEmpty() -> NavidromeEmpty(
            stringResource(R.string.music_navidrome_no_playlists)
        )
        section == NavidromeSection.ALBUMS -> LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                NavidromeBrowseRow(
                    title = album.name,
                    subtitle = album.artist,
                    coverArt = MusicNavidrome.coverUrl(
                        MusicNavidrome.config.value,
                        album.coverArt
                    ),
                    trailing = "${album.songCount} tracks",
                    onClick = { onAlbum(album) }
                )
            }
        }
        else -> LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                NavidromeBrowseRow(
                    title = playlist.name,
                    subtitle = playlist.owner ?: stringResource(R.string.music_navidrome_playlist),
                    coverArt = MusicNavidrome.coverUrl(
                        MusicNavidrome.config.value,
                        playlist.coverArt
                    ),
                    trailing = "${playlist.songCount} tracks",
                    onClick = { onPlaylist(playlist) }
                )
            }
        }
    }
    }
}

@Composable
private fun AlbumDetailContent(
    detail: NavidromeAlbumDetail,
    loading: Boolean,
    error: String?,
    playback: MusicPlayer.State,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit
) {
    Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NavidromeBackButton(onBack)
        Spacer(Modifier.width(14.dp))
        MusicArtwork(
            url = detail.album.coverArt?.let { MusicNavidrome.coverUrl(MusicNavidrome.config.value, it) },
            modifier = Modifier.size(84.dp),
            cornerRadius = 12.dp,
            iconSize = 28.dp,
            artworkPx = 480
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = detail.album.name,
                color = MusicPalette.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail.album.artist,
                color = MusicPalette.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${detail.songs.size} tracks",
                color = MusicPalette.textMuted,
                fontSize = 10.sp
            )
        }
        NavidromePill(
            icon = Icons.Default.PlayArrow,
            label = stringResource(R.string.music_play_album),
            primary = true,
            onClick = { if (detail.songs.isNotEmpty()) onPlay(detail.songs, 0) }
        )
    }
    Spacer(Modifier.height(10.dp))
    NavidromeSongList(
        songs = detail.songs,
        loading = loading,
        error = error,
        playback = playback,
        modifier = Modifier.weight(1f),
        onPlay = onPlay
    )
    }
}

@Composable
private fun PlaylistDetailContent(
    detail: NavidromePlaylistDetail,
    loading: Boolean,
    error: String?,
    playback: MusicPlayer.State,
    savedMessage: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onSave: () -> Unit
) {
    Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NavidromeBackButton(onBack)
        Spacer(Modifier.width(14.dp))
        MusicArtwork(
            url = detail.playlist.coverArt?.let {
                MusicNavidrome.coverUrl(MusicNavidrome.config.value, it)
            },
            modifier = Modifier.size(84.dp),
            cornerRadius = 12.dp,
            iconSize = 28.dp,
            artworkPx = 480
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = detail.playlist.name,
                color = MusicPalette.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail.playlist.owner ?: stringResource(R.string.music_navidrome_playlist),
                color = MusicPalette.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${detail.songs.size} tracks",
                color = MusicPalette.textMuted,
                fontSize = 10.sp
            )
            savedMessage?.let {
                Text(text = it, color = MusicPalette.accentTeal, fontSize = 10.sp)
            }
        }
        NavidromePill(
            icon = Icons.Default.PlayArrow,
            label = stringResource(R.string.music_play),
            primary = true,
            onClick = { if (detail.songs.isNotEmpty()) onPlay(detail.songs, 0) }
        )
        Spacer(Modifier.width(8.dp))
        NavidromePill(
            icon = Icons.Default.Save,
            label = stringResource(R.string.music_save_to_library),
            onClick = onSave
        )
    }
    Spacer(Modifier.height(10.dp))
    NavidromeSongList(
        songs = detail.songs,
        loading = loading,
        error = error,
        playback = playback,
        modifier = Modifier.weight(1f),
        onPlay = onPlay
    )
    }
}

@Composable
private fun NavidromeSongList(
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    playback: MusicPlayer.State,
    modifier: Modifier = Modifier,
    onPlay: (List<Song>, Int) -> Unit
) {
    when {
        loading -> MusicLoading(modifier)
        error != null -> MusicErrorState(
            message = error,
            onRetry = {},
            modifier = modifier
        )
        songs.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.music_collection_empty),
                color = MusicPalette.textSecondary,
                fontSize = 13.sp
            )
        }
        else -> LazyColumn(
            modifier,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(songs, key = { index, song -> "${song.videoId}#$index" }) { index, song ->
                MusicTrackRow(
                    song = song,
                    index = index,
                    isCurrent = playback.song?.videoId == song.videoId,
                    isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                    onClick = { onPlay(songs, index) },
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun NavidromeBrowseRow(
    title: String,
    subtitle: String,
    coverArt: String?,
    trailing: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MusicArtwork(
            url = coverArt,
            modifier = Modifier.size(48.dp),
            cornerRadius = 8.dp,
            iconSize = 20.dp,
            artworkPx = 480
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = MusicPalette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = trailing, color = MusicPalette.textMuted, fontSize = 10.sp)
    }
}

@Composable
private fun NavidromeSectionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> MusicPalette.text
                    selected -> MusicPalette.accentSoft
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                if (focused) MusicPalette.text else Color.Transparent,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) MusicPalette.background else MusicPalette.accent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = if (focused) MusicPalette.background else MusicPalette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = count.toString(),
            color = if (focused) MusicPalette.textMuted else MusicPalette.textMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun NavidromePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    !enabled -> MusicPalette.surfaceRaised
                    focused -> MusicPalette.text
                    primary -> MusicPalette.accent
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                if (focused) MusicPalette.text else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> MusicPalette.textMuted
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = when {
                !enabled -> MusicPalette.textMuted
                focused -> MusicPalette.background
                primary -> MusicPalette.background
                else -> MusicPalette.text
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NavidromeField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false
) {
    MusicTextField(
        label = label,
        value = value,
        placeholder = placeholder,
        onValueChange = onValueChange,
        password = password,
        fontSize = 13.sp
    )
}

@Composable
private fun NavidromeBackButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(2.dp, if (focused) Color.White else MusicPalette.border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun NavidromeCloseButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(2.dp, if (focused) Color.White else MusicPalette.border, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_close),
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun NavidromeEmpty(text: String) {
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = MusicPalette.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun rememberNavidromeQr(content: String?): Bitmap? = remember(content) {
    content?.let { value ->
        runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 640, 640)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height) { index ->
                if (matrix.get(index % width, index / width)) 0xFF000000.toInt()
                else 0xFFFFFFFF.toInt()
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}
