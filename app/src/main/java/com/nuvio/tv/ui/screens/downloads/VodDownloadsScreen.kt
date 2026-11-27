package com.nuvio.tv.ui.screens.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.downloads.VodDownloads
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

@Composable
fun VodDownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by VodDownloads.state.collectAsStateWithLifecycle()
    val backFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val firstDownloadFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { VodDownloads.init(context) }
    LaunchedEffect(Unit) {
        // Give TV remotes a reliable starting point when this route opens.
        runCatching { backFocusRequester.requestFocus() }
    }
    BackHandler(onBack = onBack)

    val destinations = RecordingStorage.choices(context)
    val destinationById = destinations.associateBy { it.id }
    val activeBytesByDestination = state.active.groupBy { it.destinationId }
        .mapValues { (_, items) -> items.sumOf { it.bytesDownloaded } }
    val completedBytesByDestination = state.completed.groupBy { it.destinationId }
        .mapValues { (_, items) -> items.sumOf { it.bytesDownloaded } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = 42.dp, vertical = 28.dp)
            .focusGroup()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, tint = NuvioTheme.colors.TextPrimary)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(stringResource(R.string.vod_downloads_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = stringResource(
                            R.string.vod_downloads_summary,
                            state.active.size,
                            state.completed.size,
                            RecordingStorage.sizeLabel(state.totalDownloadedBytes, Locale.getDefault())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.completed.isNotEmpty()) {
                    Button(
                        onClick = { VodDownloads.clearCompleted(context) },
                        modifier = Modifier.focusProperties {
                            down = firstDownloadFocusRequester
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.vod_downloads_clear_completed))
                    }
                }
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .focusRequester(backFocusRequester)
                        .focusProperties {
                            if (state.items.isNotEmpty()) down = firstDownloadFocusRequester
                        }
                ) { Text(stringResource(R.string.action_back)) }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.vod_downloads_storage_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            destinations.forEach { destination ->
                val total = destination.totalBytes
                val stored = (completedBytesByDestination[destination.id] ?: 0L) +
                    (activeBytesByDestination[destination.id] ?: 0L)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.size(8.dp))
                        Text(destination.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (destination.isSmb && total <= 0L) {
                            stringResource(R.string.vod_downloads_space_unknown)
                        } else {
                            stringResource(
                                R.string.vod_downloads_storage_stats,
                                RecordingStorage.sizeLabel(stored, Locale.getDefault()),
                                RecordingStorage.sizeLabel(destination.freeBytes, Locale.getDefault()),
                                if (total > 0L) RecordingStorage.sizeLabel(total, Locale.getDefault()) else stringResource(R.string.vod_downloads_space_unknown)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        if (state.items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.vod_downloads_empty), color = NuvioTheme.colors.TextSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .focusRestorer(firstDownloadFocusRequester),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    val destinationName = destinationById[item.destinationId]?.label
                        ?: stringResource(R.string.vod_downloads_storage_unavailable)
                    DownloadItemRow(
                        item = item,
                        destinationName = destinationName,
                        firstItemFocusRequester = if (index == 0) firstDownloadFocusRequester else null,
                        backFocusRequester = backFocusRequester,
                        onCancel = { VodDownloads.cancel(item.id) },
                        onDelete = { VodDownloads.remove(context, item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: VodDownloads.Item,
    destinationName: String,
    firstItemFocusRequester: FocusRequester?,
    backFocusRequester: FocusRequester,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                Text(
                    text = "${item.filename} · $destinationName",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            Button(
                onClick = if (item.status == VodDownloads.Status.DOWNLOADING) onCancel else onDelete,
                modifier = if (firstItemFocusRequester != null) {
                    Modifier
                        .focusRequester(firstItemFocusRequester)
                        .focusProperties { up = backFocusRequester }
                } else {
                    Modifier
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (item.status == VodDownloads.Status.DOWNLOADING) R.string.vod_downloads_cancel
                        else if (item.status == VodDownloads.Status.FAILED) R.string.vod_downloads_dismiss
                        else R.string.vod_downloads_delete
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (item.status) {
            VodDownloads.Status.DOWNLOADING -> {
                val total = item.totalBytes
                if (total != null && total > 0L) {
                    LinearProgressIndicator(
                        progress = { (item.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(RecordingStorage.sizeLabel(item.bytesDownloaded, Locale.getDefault()))
                        if (total != null && total > 0L) {
                            append(" / ")
                            append(RecordingStorage.sizeLabel(total, Locale.getDefault()))
                        }
                        if (item.bytesPerSecond > 0L) {
                            append(" · ")
                            append(RecordingStorage.sizeLabel(item.bytesPerSecond, Locale.getDefault()))
                            append("/s")
                        }
                        item.remainingSeconds?.let {
                            append(" · ")
                            append(stringResource(R.string.vod_downloads_time_left, formatDuration(it)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            VodDownloads.Status.COMPLETE -> Text(
                text = stringResource(R.string.vod_downloads_saved_size, RecordingStorage.sizeLabel(item.bytesDownloaded, Locale.getDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            VodDownloads.Status.FAILED -> Text(
                text = item.error ?: stringResource(R.string.vod_downloads_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val minutes = (seconds.coerceAtLeast(0L) + 59L) / 60L
    return when {
        minutes < 60L -> "${minutes}m"
        else -> "${minutes / 60L}h ${minutes % 60L}m"
    }
}
