package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.reshaped.livetv.LiveTvAddonChannel
import com.nuvio.tv.reshaped.livetv.LiveTvAddonSettings
import com.nuvio.tv.reshaped.livetv.LiveTvAddons
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Pick channels from an addon the viewer already has installed: first the addon, then which of its
 * channels to add to Live TV. [initial] is the saved pick when editing a source.
 */
@Composable
internal fun LiveTvAddonPicker(
    initial: LiveTvAddonSettings?,
    onConfirm: (LiveTvAddonSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var addons by remember { mutableStateOf<List<Addon>?>(null) }
    var chosen by remember { mutableStateOf<Addon?>(null) }
    var channels by remember { mutableStateOf<List<LiveTvAddonChannel>?>(null) }
    var selected by remember { mutableStateOf(initial?.channels?.map { it.type to it.id }?.toSet().orEmpty()) }

    LaunchedEffect(Unit) {
        val found = runCatching { LiveTvAddons.channelAddons(context) }.getOrDefault(emptyList())
        addons = found
        // Editing goes straight to the saved addon's channels.
        if (initial != null) chosen = found.firstOrNull { it.baseUrl.trimEnd('/').equals(initial.addonUrl.trimEnd('/'), ignoreCase = true) }
    }
    LaunchedEffect(chosen) {
        val addon = chosen ?: run { channels = null; return@LaunchedEffect }
        channels = null
        val listed = runCatching { LiveTvAddons.channelsOf(context, addon) }.getOrDefault(emptyList())
        // A channel picked earlier that the addon no longer lists stays pickable.
        val extra = initial?.takeIf { it.addonUrl.trimEnd('/').equals(addon.baseUrl.trimEnd('/'), ignoreCase = true) }
            ?.channels.orEmpty().filter { old -> listed.none { it.type == old.type && it.id == old.id } }
        channels = listed + extra
    }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        val currentAddons = addons
        val currentChannels = channels
        val currentChosen = chosen
        when {
            currentAddons == null -> Hint(stringResource(R.string.live_tv_addon_loading))
            currentAddons.isEmpty() -> Hint(stringResource(R.string.live_tv_addon_none))
            currentChosen == null -> {
                Hint(stringResource(R.string.live_tv_addon_pick_addon))
                currentAddons.forEach { addon ->
                    LiveTvPillButton(
                        text = addon.displayName,
                        onClick = { chosen = addon; selected = emptySet() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            currentChannels == null -> Hint(stringResource(R.string.live_tv_addon_loading))
            currentChannels.isEmpty() -> Hint(stringResource(R.string.live_tv_addon_no_channels))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvPillButton(
                        text = stringResource(R.string.live_tv_addon_select_all),
                        onClick = { selected = currentChannels.map { it.type to it.id }.toSet() },
                    )
                    LiveTvPillButton(text = stringResource(R.string.live_tv_addon_clear), onClick = { selected = emptySet() })
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                ) {
                    items(currentChannels, key = { "${it.type}|${it.id}" }) { channel ->
                        val key = channel.type to channel.id
                        LiveTvPillButton(
                            text = (if (key in selected) "✓  " else "") + channel.name +
                                (channel.group.takeIf(String::isNotBlank)?.let { "  ·  $it" } ?: ""),
                            selected = key in selected,
                            onClick = { selected = if (key in selected) selected - key else selected + key },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
        ) {
            if (currentChosen != null && initial == null) {
                LiveTvPillButton(text = stringResource(R.string.live_tv_addon_change_addon), onClick = { chosen = null })
            }
            LiveTvPillButton(text = stringResource(R.string.live_tv_back), onClick = onBack)
            if (currentChosen != null && currentChannels != null) {
                LiveTvPillButton(
                    text = stringResource(R.string.live_tv_addon_add_selected, selected.size),
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onConfirm(
                            LiveTvAddonSettings(
                                addonUrl = currentChosen.baseUrl,
                                addonName = currentChosen.displayName,
                                channels = currentChannels.filter { (it.type to it.id) in selected },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
}
