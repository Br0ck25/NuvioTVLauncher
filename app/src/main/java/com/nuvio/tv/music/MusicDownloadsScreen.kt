package com.nuvio.tv.music

import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * The viewer's downloaded music: what is on the device, what is still arriving and what
 * failed. Everything here plays from the Media3 cache, so the list also works with the
 * network unplugged.
 */
@Composable
internal fun MusicDownloadsScreen(
    onClose: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    val downloads by MusicDownloads.state.collectAsState()
    val playback by MusicPlayer.state.collectAsState()
    val closeFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    var firstRowAssigned by remember { mutableStateOf(false) }
    val completed = downloads.completed
    val active = downloads.active
    val failed = downloads.failed
    val freeBytes = remember(downloads.items) {
        runCatching {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            StatFs(root.path).availableBytes
        }.getOrDefault(0L)
    }

    LaunchedEffect(Unit) {
        MusicDownloads.init(context)
        withFrameNanos { }
        runCatching { closeFocus.requestFocus() }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MusicPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DownloadsCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.action_back),
                    modifier = Modifier.focusRequester(closeFocus),
                    onDown = { runCatching { firstRowFocus.requestFocus() } },
                    onClick = onClose
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Downloads",
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 34.sp,
                        lineHeight = 37.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("${completed.size} track")
                            if (completed.size != 1) append("s")
                            if (downloads.totalBytes > 0) {
                                append(" · ${formatDownloadBytes(downloads.totalBytes)}")
                            }
                            if (freeBytes > 0) {
                                append(" · ${formatDownloadBytes(freeBytes)} free")
                            }
                        },
                        color = MusicPalette.textMuted,
                        fontSize = 13.sp
                    )
                }
                if (active.isNotEmpty()) {
                    DownloadsPillButton(
                        icon = if (downloads.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (downloads.paused) "Resume all" else "Pause all",
                        onClick = {
                            if (downloads.paused) MusicDownloads.resumeAll()
                            else MusicDownloads.pauseAll()
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (completed.isNotEmpty() || failed.isNotEmpty()) {
                    DownloadsPillButton(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.music_action_clear_finished),
                        onClick = { MusicDownloads.clearCompleted() }
                    )
                }
            }

            if (downloads.waitingForWifi) {
                Spacer(Modifier.height(14.dp))
                DownloadsNotice(
                    icon = Icons.Default.WifiOff,
                    text = "Waiting for Wi-Fi. Downloads resume on an unmetered network."
                )
            }

            downloads.error?.let { message ->
                Spacer(Modifier.height(14.dp))
                DownloadsNotice(icon = Icons.Default.Close, text = message, error = true)
            }

            if (downloads.starting.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                DownloadsNotice(
                    icon = Icons.Default.Download,
                    text = "Preparing ${downloads.starting.size} track" +
                        if (downloads.starting.size == 1) "…" else "s…"
                )
            }

            Spacer(Modifier.height(14.dp))

            if (downloads.items.isEmpty() && downloads.starting.isEmpty()) {
                DownloadsEmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (active.isNotEmpty() || downloads.starting.isNotEmpty()) {
                        item(key = "downloads-active-header") {
                            DownloadsSectionHeader(
                                icon = Icons.Default.Download,
                                title = stringResource(R.string.music_downloads_downloading)
                            )
                        }
                        items(
                            items = active,
                            key = { "active:${it.videoId}" }
                        ) { item ->
                            DownloadRow(
                                item = item,
                                isCurrent = playback.song?.videoId == item.videoId,
                                isPlaying = playback.isPlaying &&
                                    playback.song?.videoId == item.videoId,
                                focusModifier = if (!firstRowAssigned) {
                                    Modifier.focusRequester(firstRowFocus)
                                } else {
                                    Modifier
                                },
                                onFocused = { firstRowAssigned = true },
                                onPrimary = { MusicDownloads.cancel(item.videoId) }
                            )
                        }
                    }

                    if (failed.isNotEmpty()) {
                        item(key = "downloads-failed-header") {
                            DownloadsSectionHeader(
                                icon = Icons.Default.Refresh,
                                title = stringResource(R.string.music_downloads_needs_attention)
                            )
                        }
                        items(
                            items = failed,
                            key = { "failed:${it.videoId}" }
                        ) { item ->
                            DownloadRow(
                                item = item,
                                isCurrent = false,
                                isPlaying = false,
                                focusModifier = if (!firstRowAssigned) {
                                    Modifier.focusRequester(firstRowFocus)
                                } else {
                                    Modifier
                                },
                                onFocused = { firstRowAssigned = true },
                                onPrimary = { MusicDownloads.retry(item) },
                                onDelete = { MusicDownloads.remove(item.videoId) }
                            )
                        }
                    }

                    if (completed.isNotEmpty()) {
                        item(key = "downloads-complete-header") {
                            DownloadsSectionHeader(
                                icon = Icons.Default.DownloadDone,
                                title = stringResource(R.string.music_section_on_this_device)
                            )
                        }
                        itemsIndexed(
                            items = completed,
                            key = { _, item -> "complete:${item.videoId}" }
                        ) { index, item ->
                            DownloadRow(
                                item = item,
                                isCurrent = playback.song?.videoId == item.videoId,
                                isPlaying = playback.isPlaying &&
                                    playback.song?.videoId == item.videoId,
                                focusModifier = if (!firstRowAssigned) {
                                    Modifier.focusRequester(firstRowFocus)
                                } else {
                                    Modifier
                                },
                                onFocused = { firstRowAssigned = true },
                                onPrimary = {
                                    onPlay(completed.map { it.song }, index.coerceAtLeast(0))
                                },
                                onDelete = { MusicDownloads.remove(item.videoId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: MusicDownloadItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    focusModifier: Modifier,
    onFocused: () -> Unit,
    onPrimary: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .then(focusModifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(rowShape)
            .background(
                when {
                    focused -> MusicPalette.surfaceFocused
                    isCurrent -> MusicPalette.accentSoft
                    else -> MusicPalette.surface
                }
            )
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                rowShape
            )
            .clickable(onClick = {
                onFocused()
                onPrimary()
            })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MusicArtwork(
            url = item.song.thumbnailUrl,
            modifier = Modifier.size(46.dp),
            cornerRadius = 8.dp,
            iconSize = 20.dp
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.song.title,
                color = if (isCurrent) MusicPalette.accent else MusicPalette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (item.song.artist.isNotBlank()) {
                        append(item.song.artist)
                        append(" · ")
                    }
                    append(item.statusLabel())
                },
                color = when (item.status) {
                    MusicDownloadStatus.FAILED -> MusicPalette.error
                    MusicDownloadStatus.COMPLETE -> MusicPalette.accentTeal
                    else -> MusicPalette.textSecondary
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.status == MusicDownloadStatus.DOWNLOADING ||
                item.status == MusicDownloadStatus.QUEUED ||
                item.status == MusicDownloadStatus.PAUSED
            ) {
                Spacer(Modifier.height(6.dp))
                DownloadProgressBar(
                    progress = item.progress,
                    indeterminate = item.status == MusicDownloadStatus.QUEUED
                )
            }
        }
        if (item.status == MusicDownloadStatus.COMPLETE) {
            Text(
                text = formatDownloadBytes(item.totalBytes.coerceAtLeast(item.bytes)),
                color = MusicPalette.textMuted,
                fontSize = 11.sp
            )
        }
        if (onDelete != null) {
            DownloadsRowAction(
                icon = Icons.Default.Delete,
                label = stringResource(R.string.music_action_remove_download),
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun DownloadProgressBar(progress: Float, indeterminate: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(MusicPalette.surfaceRaised)
    ) {
        if (indeterminate) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MusicPalette.accent.copy(alpha = 0.35f))
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(MusicPalette.accent)
            )
        }
    }
}

@Composable
private fun DownloadsSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
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
            fontSize = 19.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DownloadsNotice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    error: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (error) Color(0x33FF6B6B) else MusicPalette.accentSoft
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MusicPalette.error else MusicPalette.accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = if (error) MusicPalette.error else MusicPalette.textSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DownloadsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Storage,
            contentDescription = null,
            tint = MusicPalette.textMuted,
            modifier = Modifier.size(46.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "No downloads yet",
            color = MusicPalette.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Choose Download on an album, playlist or track to keep it offline.",
            color = MusicPalette.textSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DownloadsPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) MusicPalette.text else MusicPalette.surfaceRaised)
            .border(
                2.dp,
                if (focused) MusicPalette.text else MusicPalette.border,
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
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = if (focused) MusicPalette.background else MusicPalette.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DownloadsRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (focused) Color.White else MusicPalette.surfaceRaised
            )
            .border(
                1.dp,
                if (focused) Color.White else MusicPalette.border,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (focused) MusicPalette.background else MusicPalette.textSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun DownloadsCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onDown: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else MusicPalette.surfaceRaised)
            .border(
                2.dp,
                if (focused) Color.White else MusicPalette.border,
                CircleShape
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDown()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (focused) MusicPalette.background else MusicPalette.text,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun MusicDownloadItem.statusLabel(): String = when (status) {
    MusicDownloadStatus.QUEUED -> "Waiting…"
    MusicDownloadStatus.DOWNLOADING ->
        if (totalBytes > 0) {
            "Downloading · ${(progress * 100).toInt()}% of ${formatDownloadBytes(totalBytes)}"
        } else {
            "Downloading · ${(progress * 100).toInt()}%"
        }
    MusicDownloadStatus.PAUSED -> "Paused · ${(progress * 100).toInt()}%"
    MusicDownloadStatus.COMPLETE -> "Downloaded · plays offline"
    MusicDownloadStatus.FAILED -> "Download failed · choose to retry"
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1000 && index < units.lastIndex) {
        value /= 1000.0
        index++
    }
    return if (index <= 1) {
        String.format(Locale.US, "%.0f %s", value, units[index])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
