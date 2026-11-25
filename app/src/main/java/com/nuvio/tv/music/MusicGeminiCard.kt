package com.nuvio.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R

/**
 * Music → Integrations → Gemini. The key is write-only from the UI's point of view: once saved,
 * only its status is shown, never the key itself.
 */
@Composable
internal fun MusicGeminiCard(
    state: MusicGeminiUiState,
    onSaveKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onModelChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onClear: () -> Unit,
    onShareTopArtists: (Boolean) -> Unit,
    onShareLiked: (Boolean) -> Unit,
    onShareRecents: (Boolean) -> Unit,
    onSharePlaylists: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var key by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var model by remember(state.model) { mutableStateOf(state.model) }

    SourceCard(
        icon = Icons.Default.MusicNote,
        tint = MusicPalette.accentTeal,
        title = stringResource(R.string.music_gemini_title),
        subtitle = stringResource(R.string.music_gemini_subtitle),
    ) {
        if (state.configured && !replacing) {
            Text(
                text = stringResource(R.string.music_gemini_configured),
                color = MusicPalette.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SourcePill(
                    icon = Icons.Default.Cloud,
                    label = stringResource(R.string.music_gemini_replace),
                    onClick = { replacing = true },
                )
                SourcePill(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.music_gemini_remove),
                    onClick = {
                        onRemoveKey()
                        key = ""
                        replacing = false
                    },
                )
                SourcePill(
                    icon = Icons.Default.PlayArrow,
                    label = if (state.enabled) "Suggestions on" else "Suggestions off",
                    primary = state.enabled,
                    onClick = { onSetEnabled(!state.enabled) },
                )
            }
        } else {
            MusicTextField(
                label = stringResource(R.string.music_gemini_key),
                value = key,
                placeholder = stringResource(R.string.music_gemini_key_placeholder),
                onValueChange = { key = it },
                password = true,
                modifier = Modifier.fillMaxWidth(),
                onDone = {
                    if (key.isNotBlank()) {
                        onSaveKey(key)
                        key = ""
                        replacing = false
                    }
                },
            )
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SourcePill(
                    icon = Icons.Default.Cloud,
                    label = stringResource(R.string.music_gemini_save),
                    primary = true,
                    enabled = key.isNotBlank(),
                    onClick = {
                        onSaveKey(key)
                        key = ""
                        replacing = false
                    },
                )
                if (state.configured) {
                    SourcePill(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.music_gemini_cancel),
                        onClick = {
                            key = ""
                            replacing = false
                        },
                    )
                }
            }
            if (!state.configured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.music_gemini_no_key),
                    color = MusicPalette.textMuted,
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        MusicTextField(
            label = stringResource(R.string.music_gemini_model),
            value = model,
            placeholder = GeminiSettings.DEFAULT_MODEL,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            onDone = { onModelChange(model) },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.music_gemini_share_title),
            color = MusicPalette.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourcePill(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.music_gemini_top_artists),
                primary = state.shareTopArtists,
                onClick = { onShareTopArtists(!state.shareTopArtists) },
            )
            SourcePill(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.music_gemini_liked),
                primary = state.shareLiked,
                onClick = { onShareLiked(!state.shareLiked) },
            )
            SourcePill(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.music_gemini_recents),
                primary = state.shareRecents,
                onClick = { onShareRecents(!state.shareRecents) },
            )
            SourcePill(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.music_gemini_playlists),
                primary = state.sharePlaylists,
                onClick = { onSharePlaylists(!state.sharePlaylists) },
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SourcePill(
                icon = Icons.Default.PlayArrow,
                label = if (state.generating) stringResource(R.string.music_gemini_generating)
                else stringResource(R.string.music_gemini_generate),
                primary = true,
                enabled = state.configured && !state.generating,
                onClick = onGenerate,
            )
            if (state.tracks.isNotEmpty() || state.albums.isNotEmpty()) {
                SourcePill(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.music_gemini_clear),
                    onClick = onClear,
                )
            }
        }

        state.error?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, color = MusicPalette.error, fontSize = 11.sp)
        }
        state.notice?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, color = MusicPalette.accentTeal, fontSize = 11.sp)
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(R.string.music_gemini_note),
            color = MusicPalette.textMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}
