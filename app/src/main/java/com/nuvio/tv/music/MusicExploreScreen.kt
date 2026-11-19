package com.nuvio.tv.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.nuvio.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Mood tiles fetch their cover two at a time; the repository caches the shelves. */
private val moodCoverGate = Semaphore(2)

@Composable
internal fun MusicExploreScreen(
    state: MusicExploreState,
    onMoodClick: (MoodGenre) -> Unit,
    onMoodBack: () -> Unit,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    onPlayShelfItem: (HomeShelf, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = state.selected
    if (selected == null) {
        val hasAnything = state.sections.isNotEmpty() ||
            state.releases.isNotEmpty() || state.charts.isNotEmpty()
        when {
            state.loading && !hasAnything -> MusicLoading(modifier.fillMaxSize())
            state.error != null && !hasAnything ->
                MusicErrorState(state.error, onRetry, modifier)
            !hasAnything -> MusicEmptyState(
                title = stringResource(R.string.music_p2_nothing_to_explore),
                message = stringResource(R.string.music_p2_service_no_moods),
                modifier = modifier
            )
            else -> MoodList(
                state = state,
                onMoodClick = onMoodClick,
                onShelfItemClick = onShelfItemClick,
                modifier = modifier
            )
        }
    } else {
        MoodDetail(
            state = state,
            onBack = onMoodBack,
            onRetry = { selected?.let(onMoodClick) },
            onShelfItemClick = onShelfItemClick,
            onPlayShelfItem = onPlayShelfItem,
            modifier = modifier
        )
    }
}

@Composable
private fun MoodList(
    state: MusicExploreState,
    onMoodClick: (MoodGenre) -> Unit,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstMoodFocus = remember { FocusRequester() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "explore-header") {
            ExploreHeader()
        }
        itemsIndexed(
            items = state.sections,
            key = { index, section -> "${section.title}#$index" }
        ) { sectionIndex, section ->
            Column {
                // The mockup goes straight from the screen head into the mood grid. Keep the
                // label only when the server actually returns several groups.
                if (state.sections.size > 1) {
                    MusicSectionHeader(title = section.title)
                    Spacer(Modifier.height(10.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    section.items.chunked(3).forEachIndexed { rowIndex, row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEachIndexed { columnIndex, mood ->
                                val absoluteIndex = rowIndex * 3 + columnIndex
                                MoodTile(
                                    mood = mood,
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(
                                            if (sectionIndex == 0 && absoluteIndex == 0) {
                                                Modifier.focusRequester(firstMoodFocus)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    onClick = { onMoodClick(mood) }
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        if (state.releases.isNotEmpty()) {
            item(key = "explore-releases-header") {
                MusicSectionHeader(title = stringResource(R.string.music_explore_new_releases))
            }
            itemsIndexed(
                items = state.releases,
                key = { index, shelf -> "release#${shelf.title}#$index" }
            ) { _, shelf ->
                MusicShelfRow(
                    shelf = shelf,
                    onItemClick = { _, index -> onShelfItemClick(shelf, index) }
                )
            }
        }
        if (state.charts.isNotEmpty()) {
            item(key = "explore-charts-header") {
                MusicSectionHeader(title = stringResource(R.string.music_explore_charts))
            }
            itemsIndexed(
                items = state.charts,
                key = { index, shelf -> "chart#${shelf.title}#$index" }
            ) { _, shelf ->
                MusicShelfRow(
                    shelf = shelf,
                    onItemClick = { _, index -> onShelfItemClick(shelf, index) }
                )
            }
        }
        if (state.extrasLoading && state.releases.isEmpty() && state.charts.isEmpty()) {
            item(key = "explore-extras-loading") {
                Text(
                    text = stringResource(R.string.music_explore_loading),
                    color = MusicPalette.textSecondary,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
            }
        }
    }
}

@Composable
private fun ExploreHeader() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
        Text(
            text = stringResource(R.string.music_tab_explore),
            color = MusicPalette.text,
            fontFamily = MusicDisplay,
            fontSize = 40.sp,
            lineHeight = 43.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.music_p2_moods_moments_and_genres_for),
            color = MusicPalette.textSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun MoodTile(
    mood: MoodGenre,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var coverUrl by remember(mood.browseId, mood.params) {
        mutableStateOf(
            mood.thumbnailUrl ?: MusicMoodCovers.get(context, mood.browseId, mood.params)
        )
    }
    LaunchedEffect(mood.browseId, mood.params) {
        if (coverUrl != null) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) {
            moodCoverGate.withPermit {
                runCatching {
                    YtMusicRepository.moodGenreArtwork(mood.browseId, mood.params).getOrNull()
                }.getOrNull()
            }
        }
        if (url != null) {
            coverUrl = url
            MusicMoodCovers.put(context, mood.browseId, mood.params, url)
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(140),
        label = "moodTileScale"
    )
    val base = mood.stripeColor?.let { Color(it) } ?: MusicPalette.accentPurple
    // YouTube's stripe colours range from near-black to pale yellow. Keep every tile
    // recognisably its own hue, but pull the whole set toward ink so the title can stay white.
    val light = lerp(base, MusicPalette.background, 0.46f)
    val dark = lerp(base, MusicPalette.background, 0.64f)
    val shape = RoundedCornerShape(15.dp)
    val background = Brush.linearGradient(listOf(light, dark))
    Box(
        modifier
            .height(109.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(15.dp)
    ) {
        coverUrl?.let { url ->
            MusicArtwork(
                url = url,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.42f },
                cornerRadius = 15.dp
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }
        // The oversized ring is the mockup's quiet visual signature for a mood tile.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 34.dp, y = 40.dp)
                .size(124.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        )
        Text(
            text = mood.title,
            color = Color.White,
            fontFamily = MusicDisplay,
            fontSize = 22.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = stringResource(R.string.music_p2_browse),
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun MoodDetail(
    state: MusicExploreState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    onPlayShelfItem: (HomeShelf, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val mood = state.selected ?: return
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(mood.browseId) {
        runCatching { backFocus.requestFocus() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "mood-header") {
            Row(
                Modifier.padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (focused) Color.White else MusicPalette.surfaceRaised)
                        .border(
                            2.dp,
                            if (focused) Color.White else MusicPalette.border,
                            RoundedCornerShape(50)
                        )
                        .focusRequester(backFocus)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.music_p2_back),
                        tint = if (focused) MusicPalette.background else MusicPalette.text,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = mood.title,
                    color = MusicPalette.text,
                    fontFamily = MusicDisplay,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        when {
            state.selectedLoading -> item(key = "mood-loading") {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    MusicLoading(Modifier.fillMaxSize())
                }
            }
            state.selectedError != null -> item(key = "mood-error") {
                MusicErrorState(
                    message = state.selectedError,
                    onRetry = onRetry,
                    modifier = Modifier.height(260.dp)
                )
            }
            else -> itemsIndexed(
                items = state.selectedShelves,
                key = { index, shelf -> "${shelf.title}#$index" }
            ) { _, shelf ->
                MusicShelfRow(
                    shelf = shelf,
                    onItemClick = { _, index -> onShelfItemClick(shelf, index) }
                )
            }
        }
    }
}
