package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Long-press menu for a copy in the backup channels lists (the bar's Backups button and the
 * Right-twice flyout): the guide's usual channel options that make sense while watching —
 * switch to it, start the show over, record, favorites and groups, rename, renumber, hide.
 *
 * [onSwitch] plays this copy (and closes the lists); [onRestarted] is called after the show was
 * started over on it. [onDismiss] goes back to the list.
 */
@Composable
internal fun BackupChannelMenu(
    channel: LiveChannel,
    viewModel: LiveTvViewModel,
    playing: Boolean,
    onSwitch: () -> Unit,
    onRestarted: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf("menu") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val first = remember { FocusRequester() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val series by viewModel.seriesRules.collectAsStateWithLifecycle()
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    when (step) {
        "rename" -> TextInputDialog(
            title = stringResource(R.string.iptv_ge_rename_channel),
            initial = channel.name,
            hint = stringResource(R.string.iptv_ge_leave_name),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = onDismiss,
            onConfirm = { viewModel.renameChannel(channel, it); onDismiss() }
        )
        "number" -> TextInputDialog(
            title = stringResource(R.string.iptv_ge_channel_number),
            initial = channel.number.toString(),
            hint = stringResource(R.string.iptv_ge_leave_number),
            confirmLabel = stringResource(R.string.action_save),
            numeric = true,
            onDismiss = onDismiss,
            onConfirm = { viewModel.setChannelNumber(channel, it.trim().toIntOrNull()); onDismiss() }
        )
        "newgroup" -> TextInputDialog(
            title = stringResource(R.string.iptv_ge_new_group),
            initial = "",
            hint = stringResource(R.string.iptv_ge_group_name),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = onDismiss,
            onConfirm = { name -> viewModel.createGroupWith(name, channel); onDismiss() }
        )
        "group" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.uiState.value.customGroups,
            onDismiss = onDismiss,
            onPick = { g -> viewModel.addToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        "copy" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.copyTargets(channel),
            title = stringResource(R.string.iptv_ge_copy_named, "\"${channel.name}\""),
            onDismiss = onDismiss,
            onPick = { g -> viewModel.copyToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        "manual" -> com.nuvio.tv.livetv.record.ManualRecordingDialog(
            channel = channel,
            use24h = settings.use24HourClock,
            onSchedule = { title, start, stop ->
                LiveTvNotice.show(viewModel.scheduler.addManual(channel, title, start, stop))
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> {
            val program = viewModel.currentProgram(channel.key, now)
            val isFavorite = channel.key in user.favorites
            val recordingNow = recordings.any { it.channelKey == channel.key && it.isRecording }
            val scheduled = program != null && schedules.any { it.channel.key == channel.key && it.startMs == program.startMs }
            val recordLabel = when {
                recordingNow -> stringResource(R.string.iptv_ge_stop_recording)
                scheduled -> stringResource(R.string.iptv_ge_cancel_recording)
                else -> stringResource(R.string.iptv_guide_record)
            }
            val seriesLabel = program?.let { p ->
                val n = com.nuvio.tv.livetv.data.LiveTvRepository.normalize(titleMarks(p.title).title)
                if (series.any { it.channel.key == channel.key && it.norm == n }) stringResource(R.string.iptv_ge_stop_recording_series) else stringResource(R.string.iptv_ge_record_series)
            }
            val canRestart = viewModel.canWatchFromStart(channel)
            val bell = com.nuvio.tv.livetv.calendar.rememberLiveBell(program, channel)
            val from = playlistNames[channel.sourceId] ?: channel.sourceName
            LiveDialog(onDismiss = onDismiss, width = 460.dp) {
                LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
                LiveText(
                    listOfNotNull(from.takeIf { it.isNotBlank() }, channel.group.takeIf { it.isNotBlank() }, if (playing) stringResource(R.string.iptv_ge_watching) else null)
                        .joinToString(" · "),
                    color = NuvioTheme.colors.TextSecondary, size = 13.sp
                )
                program?.let { LiveText(stringResource(R.string.iptv_ge_now, it.title), color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 1) }
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!playing || canRestart) item { MenuHeader(stringResource(R.string.iptv_guide_watch), first = true) }
                    if (!playing) item { MenuItem(stringResource(R.string.iptv_ge_switch_copy), Modifier.focusRequester(first), onSwitch) }
                    if (canRestart) item {
                        MenuItem(viewModel.restartLabel(channel), if (playing) Modifier.focusRequester(first) else Modifier) {
                            if (viewModel.watchFromStart(channel)) onRestarted() else onDismiss()
                        }
                    }
                    if (bell != null) {
                        item { MenuHeader(stringResource(R.string.iptv_ge_this_show)) }
                        item { MenuItem(bell.label, onClick = { bell.toggle(); onDismiss() }) }
                    }
                    item { MenuHeader(stringResource(R.string.iptv_ge_record_header), first = playing && !canRestart) }
                    item {
                        MenuItem(recordLabel, if (playing && !canRestart) Modifier.focusRequester(first) else Modifier) {
                            val msg = when {
                                recordingNow -> viewModel.toggleRecording(channel)
                                program != null -> viewModel.toggleScheduled(channel, program)
                                // No listings: record from now (an hour, as the play bar's Record does).
                                else -> viewModel.toggleRecording(channel)
                            }
                            LiveTvNotice.show(msg)
                            onDismiss()
                        }
                    }
                    if (program != null && seriesLabel != null) item {
                        MenuItem(seriesLabel) {
                            LiveTvNotice.show(viewModel.toggleSeries(channel, program))
                            onDismiss()
                        }
                    }
                    item { MenuItem(stringResource(R.string.iptv_ge_schedule_recording), onClick = { step = "manual" }) }
                    item { MenuHeader(stringResource(R.string.iptv_ge_favorites_groups)) }
                    item {
                        MenuItem(if (isFavorite) stringResource(R.string.iptv_ge_remove_favorites) else stringResource(R.string.iptv_ge_add_favorites)) {
                            viewModel.toggleFavorite(channel)
                            onDismiss()
                        }
                    }
                    item { MenuItem(stringResource(R.string.iptv_ge_add_to_group), onClick = { step = "group" }) }
                    item { MenuItem(stringResource(R.string.iptv_ge_copy_channel), onClick = { step = "copy" }) }
                    item { MenuHeader(stringResource(R.string.iptv_ge_edit_channel)) }
                    item { MenuItem(stringResource(R.string.iptv_ge_rename_channel), onClick = { step = "rename" }) }
                    item { MenuItem(stringResource(R.string.iptv_ge_change_number), onClick = { step = "number" }) }
                    item {
                        MenuItem(stringResource(R.string.iptv_gear_hide_channel)) {
                            viewModel.hideChannel(channel)
                            LiveTvNotice.show(context.getString(R.string.iptv_ge_channel_hidden, channel.name))
                            onDismiss()
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        }
    }
}
