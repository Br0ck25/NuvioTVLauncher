package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Settings-only search. It finds settings pages and the important rows inside them; picking
 * a Playback row opens that collapsible section instead of leaving the user at the top.
 */
@Composable
internal fun SettingsSearchOverlay(
    sections: List<SettingsSectionSpec>,
    isEssentialMode: Boolean,
    onDismiss: () -> Unit,
    onOpen: (SettingsSearchResult) -> Unit
) {
    BackHandler { onDismiss() }

    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }

    val index = remember(sections, isEssentialMode) {
        settingsSearchIndex(sections, isEssentialMode)
    }
    val results = remember(index, query) { searchSettings(index, query) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                SettingsDetailHeader(
                    title = "Search settings",
                    subtitle = "Find a setting by name — subtitles, HDR, buffer, CloudStream…"
                )

                NuvioTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Type to search settings…",
                    focusRequester = fieldFocus,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                )

                when {
                    query.isBlank() -> SettingsNote(
                        text = "Try “subtitles”, “audio”, “HDR”, “buffer”, “plugins” or “CloudStream”."
                    )
                    results.isEmpty() -> SettingsNote(
                        text = "No settings match “$query”."
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        itemsIndexed(
                            items = results,
                            key = { _, result -> result.id }
                        ) { _, result ->
                            SettingsActionRow(
                                title = result.title,
                                subtitle = result.subtitle,
                                leadingIcon = result.icon,
                                onClick = { onOpen(result) }
                            )
                        }
                    }
                }

                Text(
                    text = "Press Back to close search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
    }
}
