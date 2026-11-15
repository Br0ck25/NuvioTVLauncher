package com.nuvio.tv.music

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.music.bitchord.data.model.Song
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun MusicLocalScreen(
    onPlay: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tracks by LocalMusicRepository.tracks.collectAsState()
    val scanning by LocalMusicRepository.scanning.collectAsState()
    val error by LocalMusicRepository.error.collectAsState()
    val playback by MusicPlayer.state.collectAsState()
    val share by MusicShareServerManager.state.collectAsState()
    val drive by MusicDriveUploader.state.collectAsState()
    LaunchedEffect(Unit) {
        MusicDriveUploader.init(context)
    }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val hasPermission = remember(permissionRevision) {
        LocalMusicRepository.hasPermission(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRevision++
        if (granted) LocalMusicRepository.scan(context)
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && tracks.isEmpty() && !scanning) {
            LocalMusicRepository.scan(context)
        }
    }

    val scanFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "local-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_p2_local_music),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 40.sp,
                        lineHeight = 43.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.music_p2_music_on_this_tv_usb),
                        color = MusicPalette.textSecondary,
                        fontSize = 14.sp
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LocalActionButton(
                        icon = Icons.Default.Refresh,
                        label = if (scanning) stringResource(R.string.music_p2_scanning) else stringResource(R.string.music_p2_scan),
                        enabled = !scanning,
                        modifier = Modifier.focusRequester(scanFocus),
                        onClick = {
                            if (hasPermission) {
                                LocalMusicRepository.scan(context)
                            } else {
                                permissionLauncher.launch(LocalMusicRepository.requiredPermission())
                            }
                        }
                    )
                    LocalActionButton(
                        icon = if (share.running) Icons.Default.Close else Icons.Default.Share,
                        label = if (share.running) stringResource(R.string.music_p2_stop) else stringResource(R.string.music_p2_share),
                        enabled = share.running || (hasPermission && tracks.isNotEmpty()),
                        onClick = {
                            if (share.running) {
                                MusicShareServerManager.stop()
                            } else if (hasPermission) {
                                MusicShareServerManager.start(context)
                            } else {
                                permissionLauncher.launch(LocalMusicRepository.requiredPermission())
                            }
                        }
                    )
                    LocalActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = when {
                            drive.busy -> stringResource(R.string.music_p2_working)
                            drive.signIn != null -> stringResource(R.string.music_p2_cancel)
                            drive.connected -> stringResource(R.string.music_p2_upload)
                            else -> stringResource(R.string.music_p2_drive)
                        },
                        enabled = !drive.busy,
                        onClick = {
                            when {
                                drive.signIn != null -> MusicDriveUploader.cancelSignIn()
                                drive.connected -> {
                                    if (tracks.isNotEmpty()) MusicDriveUploader.uploadAll(context)
                                }
                                else -> MusicDriveUploader.connect(context)
                            }
                        }
                    )
                }
            }
        }

        if (share.running || share.error != null) {
            item(key = "local-share") {
                LocalSharePanel(
                    share = share,
                    trackCount = tracks.size,
                    hasPermission = hasPermission,
                    onStart = {
                        if (hasPermission) {
                            MusicShareServerManager.start(context)
                        } else {
                            permissionLauncher.launch(LocalMusicRepository.requiredPermission())
                        }
                    },
                    onStop = { MusicShareServerManager.stop() }
                )
            }
        }

        if (drive.connected || drive.signIn != null || drive.busy || drive.message != null) {
            item(key = "local-drive") {
                LocalDrivePanel(
                    state = drive,
                    trackCount = tracks.size,
                    onConnect = { MusicDriveUploader.connect(context) },
                    onCancel = { MusicDriveUploader.cancelSignIn() },
                    onUpload = { MusicDriveUploader.uploadAll(context) },
                    onDisconnect = { MusicDriveUploader.disconnect() }
                )
            }
        }

        if (!hasPermission) {
            item(key = "local-permission") {
                LocalNotice(
                    icon = Icons.Default.Usb,
                    title = stringResource(R.string.music_p2_allow_storage_access),
                    message = stringResource(R.string.music_p2_nuvio_needs_permission_to_read),
                    actionLabel = stringResource(R.string.music_p2_allow_access),
                    onClick = {
                        permissionLauncher.launch(LocalMusicRepository.requiredPermission())
                    },
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
            }
        } else if (scanning) {
            item(key = "local-scanning") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(
                        color = MusicPalette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.music_p2_scanning_the_device_and_connected),
                        color = MusicPalette.textSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else if (error != null) {
            item(key = "local-error") {
                MusicErrorState(
                    message = error.orEmpty(),
                    onRetry = { LocalMusicRepository.scan(context) },
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                )
            }
        } else if (tracks.isEmpty()) {
            item(key = "local-empty") {
                LocalNotice(
                    icon = Icons.Default.Usb,
                    title = stringResource(R.string.music_p2_no_music_found),
                    message = stringResource(R.string.music_p2_plug_in_a_usb_drive),
                    actionLabel = stringResource(R.string.music_p2_scan_again),
                    onClick = { LocalMusicRepository.scan(context) },
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
            }
        } else {
            item(key = "local-count") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.music_section_on_this_device),
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${tracks.size} track" + if (tracks.size == 1) "" else "s",
                        color = MusicPalette.textMuted,
                        fontSize = 11.5.sp
                    )
                }
            }
            itemsIndexed(
                items = tracks,
                key = { index, song -> "${song.videoId}#$index" }
            ) { index, song ->
                MusicTrackRow(
                    song = song,
                    index = index,
                    isCurrent = playback.song?.videoId == song.videoId,
                    isPlaying = playback.isPlaying && playback.song?.videoId == song.videoId,
                    onClick = { onPlay(tracks, index) },
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalSharePanel(
    share: MusicShareState,
    trackCount: Int,
    hasPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val qrBitmap = rememberQrBitmap(share.url)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MusicPalette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.music_p2_transfer_to_a_computer),
                    color = MusicPalette.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.music_p2_open_the_address_in_a),
                    color = MusicPalette.textSecondary,
                    fontSize = 13.sp
                )
            }
            if (share.running) {
                LocalActionButton(
                    icon = Icons.Default.Close,
                    label = stringResource(R.string.music_p2_stop_sharing),
                    onClick = onStop
                )
            } else {
                LocalActionButton(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.music_p2_share_over_network),
                    enabled = hasPermission && trackCount > 0,
                    onClick = onStart
                )
            }
        }

        if (share.running) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.music_p2_share_page_qr_code),
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_p2_on_your_computer_open),
                        color = MusicPalette.textSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = share.url ?: stringResource(R.string.music_p2_finding_the_tv_s_network),
                        color = MusicPalette.accent,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.music_p2_the_page_lists_every_local),
                        color = MusicPalette.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
            share.error?.let { error ->
                Text(
                    text = error,
                    color = MusicPalette.error,
                    fontSize = 13.sp
                )
            }
        } else if (!hasPermission) {
            Text(
                text = stringResource(R.string.music_p2_allow_storage_access_above_to),
                color = MusicPalette.textMuted,
                fontSize = 12.sp
            )
        } else if (trackCount == 0) {
            Text(
                text = stringResource(R.string.music_p2_scan_the_device_and_any),
                color = MusicPalette.textMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun LocalDrivePanel(
    state: MusicDriveState,
    trackCount: Int,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onUpload: () -> Unit,
    onDisconnect: () -> Unit
) {
    val signInQr = rememberQrBitmap(state.signIn?.qrUrl)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MusicPalette.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.music_p2_copy_to_google_drive),
                    color = MusicPalette.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.music_p2_uploads_local_tracks_to_a),
                    color = MusicPalette.textSecondary,
                    fontSize = 13.sp
                )
            }
            when {
                state.busy -> {
                    CircularProgressIndicator(
                        color = MusicPalette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
                state.connected -> {
                    LocalActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.music_p2_upload_local_music),
                        enabled = trackCount > 0,
                        onClick = onUpload
                    )
                }
                else -> {
                    LocalActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.music_p2_connect_google_drive),
                        onClick = onConnect
                    )
                }
            }
        }

        state.signIn?.let { prompt ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (signInQr != null) {
                    Image(
                        bitmap = signInQr.asImageBitmap(),
                        contentDescription = stringResource(R.string.music_p2_google_sign_in_qr_code),
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "On your phone, open ${prompt.url}",
                        color = MusicPalette.textSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = prompt.userCode,
                        color = MusicPalette.accent,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.music_p2_waiting_for_approval),
                        color = MusicPalette.textMuted,
                        fontSize = 12.sp
                    )
                }
                LocalActionButton(
                    icon = Icons.Default.Close,
                    label = stringResource(R.string.music_p2_cancel),
                    onClick = onCancel
                )
            }
        }

        if (state.connected) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.email?.let { stringResource(R.string.music_p2_connected_as, it) }
                            ?: stringResource(R.string.music_p2_google_drive_connected),
                        color = MusicPalette.textSecondary,
                        fontSize = 13.sp
                    )
                    if (state.busy) {
                        Text(
                            text = "Uploading ${state.uploaded} of ${state.total}…",
                            color = MusicPalette.text,
                            fontSize = 13.sp
                        )
                    }
                    state.folderUrl?.let { url ->
                        Text(
                            text = "Drive folder: $url",
                            color = MusicPalette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                LocalActionButton(
                    icon = Icons.Default.Logout,
                    label = stringResource(R.string.music_p2_disconnect),
                    onClick = onDisconnect
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = if (state.connected) MusicPalette.textMuted else MusicPalette.textSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String?): Bitmap? = remember(content) {
    content?.let { value ->
        runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height) { index ->
                if (matrix.get(index % width, index / width)) {
                    0xFF000000.toInt()
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}

@Composable
private fun LocalActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused && enabled -> Color.White
                    else -> MusicPalette.surfaceRaised
                }
            )
            .border(
                2.dp,
                if (focused && enabled) Color.White else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused && enabled) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (focused && enabled) MusicPalette.background else MusicPalette.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LocalNotice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MusicPalette.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MusicPalette.textSecondary,
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = title,
                color = MusicPalette.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = message,
            color = MusicPalette.textSecondary,
            fontSize = 14.sp
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (focused) Color.White else MusicPalette.accent)
                .border(
                    2.dp,
                    if (focused) Color.White else Color.Transparent,
                    RoundedCornerShape(50)
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 9.dp)
        ) {
            Text(
                text = actionLabel,
                color = if (focused) MusicPalette.background else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
