package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveTvGroupTags
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Manual group-tag scan and selection. The scan only runs when the user presses Scan tags;
 * choosing a tag hides its groups, except channels that are favorited (and the other
 * channels the guide already keeps visible outside their group).
 */
@Composable
internal fun GroupTagsDialog(
    tags: List<LiveTvGroupTags.Tag>,
    selected: Set<String>,
    scanning: Boolean,
    channelCount: Int,
    onScan: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_settings_filter_by_group_tag),
        subtitle = stringResource(R.string.iptv_settings_group_tags_hint),
        width = 720.dp
    ) {
        Text(
            text = stringResource(R.string.iptv_settings_filter_by_group_tag_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        SettingsActionRow(
            title = when {
                scanning -> stringResource(R.string.iptv_settings_group_tags_scanning)
                tags.isEmpty() -> stringResource(R.string.iptv_settings_group_tags_scan)
                else -> stringResource(R.string.iptv_settings_group_tags_scan_again)
            },
            subtitle = if (channelCount == 0) {
                stringResource(R.string.iptv_settings_group_tags_no_channels)
            } else {
                null
            },
            value = selected.size.takeIf { it > 0 }?.let {
                stringResource(R.string.iptv_settings_group_tags_selected, it)
            },
            onClick = onScan,
            modifier = Modifier.focusRequester(first),
            enabled = !scanning && channelCount > 0
        )

        if (tags.isNotEmpty() && !scanning) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                items(tags, key = { it.label }) { tag ->
                    val isSelected = selected.any { it.equals(tag.label, ignoreCase = true) }
                    SettingsToggleRow(
                        title = tag.label,
                        subtitle = stringResource(
                            R.string.iptv_settings_group_tags_count,
                            tag.channelCount,
                            tag.groupCount
                        ),
                        checked = isSelected,
                        onToggle = { onToggle(tag.label, !isSelected) }
                    )
                }
            }
        } else if (!scanning) {
            Text(
                text = stringResource(R.string.iptv_settings_group_tags_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        SettingsDialogActionRow {
            if (selected.isNotEmpty()) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.iptv_settings_group_tags_clear),
                    onClick = onClear
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.iptv_settings_group_tags_done),
                onClick = onDismiss,
                primary = true
            )
        }
    }
}
