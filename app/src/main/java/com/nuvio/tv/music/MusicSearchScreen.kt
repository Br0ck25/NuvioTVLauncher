package com.nuvio.tv.music

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun MusicSearchScreen(
    state: MusicSearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onFilterChange: (SearchFilter) -> Unit,
    onTrackClick: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val boxFocus = remember { FocusRequester() }
    val textFocus = remember { FocusRequester() }
    val firstFilterFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    val playback by MusicPlayer.state.collectAsState()

    LaunchedEffect(editing) {
        if (editing) runCatching { textFocus.requestFocus() }
    }

    BackHandler(enabled = editing) {
        editing = false
        keyboard?.hide()
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.rows.size) {
        if (nearEnd && state.submitted) onLoadMore()
    }

    Column(modifier.fillMaxSize()) {
        // ---- search box + filters ----
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 26.dp, end = 26.dp, top = 22.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MusicPalette.surface)
                .border(
                    2.dp,
                    if (boxFocused || editing) MusicPalette.accent else MusicPalette.border,
                    RoundedCornerShape(26.dp)
                )
                .focusProperties { down = firstFilterFocus }
                .focusRequester(boxFocus)
                .onFocusChanged { boxFocused = it.isFocused }
                .clickable {
                    editing = true
                    runCatching { textFocus.requestFocus() }
                    keyboard?.show()
                }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MusicPalette.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
                BasicTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MusicPalette.text,
                        fontSize = 16.sp,
                        fontFamily = MusicSans
                    ),
                    cursorBrush = SolidColor(MusicPalette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        editing = false
                        onSubmit(state.query)
                        runCatching { firstFilterFocus.requestFocus() }
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(textFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged {
                            if (!it.isFocused && editing) editing = false
                        },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.music_p2_search_songs_albums_artists_and),
                                color = MusicPalette.textMuted,
                                fontSize = 16.sp
                            )
                        }
                        inner()
                    }
                )
            }
        }

        Row(
            Modifier.padding(start = 26.dp, end = 26.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchFilter.entries.forEachIndexed { index, filter ->
                MusicPill(
                    label = filter.label,
                    selected = state.filter == filter,
                    compact = true,
                    onClick = { onFilterChange(filter) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFilterFocus) else Modifier
                )
            }
        }

        if (state.suggestions.isNotEmpty() && state.query.isNotBlank()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                itemsIndexed(state.suggestions) { _, suggestion ->
                    SuggestionPill(suggestion) {
                        keyboard?.hide()
                        editing = false
                        onQueryChange(suggestion)
                        onSubmit(suggestion)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            // Addon answers can arrive before YouTube's; show them instead of hiding the list.
            state.loading && state.rows.isEmpty() -> MusicLoading(Modifier.weight(1f).fillMaxWidth())
            !state.submitted -> MusicEmptyState(
                title = stringResource(R.string.music_p2_search_music),
                message = stringResource(R.string.music_p2_find_a_song_an_album),
                modifier = Modifier.weight(1f)
            )
            state.error != null && state.rows.isEmpty() -> MusicErrorState(
                message = state.error,
                onRetry = { onSubmit(state.query) },
                modifier = Modifier.weight(1f)
            )
            state.rows.isEmpty() -> MusicEmptyState(
                title = stringResource(R.string.music_p2_no_results),
                message = "Nothing matched \"${state.query}\". Try another spelling.",
                modifier = Modifier.weight(1f)
            )
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.music_p2_results),
                            color = MusicPalette.text,
                            fontFamily = MusicDisplay,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = state.rows.size.toString(),
                            color = MusicPalette.textMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = state.rows,
                            key = { index, row ->
                                when (row) {
                                    is SearchResult.TopTrack -> "t:${row.song.videoId}#$index"
                                    is SearchResult.Track -> "t:${row.song.videoId}#$index"
                                    is SearchResult.Browse -> "b:${row.item.browseId}#$index"
                                }
                            }
                        ) { _, row ->
                            when (row) {
                                is SearchResult.TopTrack -> TrackResult(
                                    song = row.song,
                                    state = state,
                                    playingSongId = playback.song?.videoId,
                                    playing = playback.isPlaying,
                                    onTrackClick = onTrackClick
                                )
                                is SearchResult.Track -> TrackResult(
                                    song = row.song,
                                    state = state,
                                    playingSongId = playback.song?.videoId,
                                    playing = playback.isPlaying,
                                    onTrackClick = onTrackClick
                                )
                                is SearchResult.Browse -> MusicBrowseRow(
                                    title = row.item.title,
                                    subtitle = row.item.subtitle,
                                    thumbnailUrl = row.item.thumbnailUrl,
                                    type = row.item.type,
                                    onClick = { onBrowseClick(row.item) }
                                )
                            }
                        }
                        if (state.loadingMore) {
                            item(key = "search-loading-more") {
                                Box(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = MusicPalette.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackResult(
    song: Song,
    state: MusicSearchState,
    playingSongId: String?,
    playing: Boolean,
    onTrackClick: (Song) -> Unit
) {
    val index = state.songs.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
    MusicTrackRow(
        song = song,
        index = index,
        isCurrent = playingSongId == song.videoId,
        isPlaying = playing && playingSongId == song.videoId,
        onClick = { onTrackClick(song) }
    )
}

@Composable
private fun SuggestionPill(
    text: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) MusicPalette.surfaceFocused else MusicPalette.surface)
            .border(
                2.dp,
                if (focused) MusicPalette.text else MusicPalette.border,
                RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MusicPalette.text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
