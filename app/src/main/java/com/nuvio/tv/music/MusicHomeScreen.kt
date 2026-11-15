package com.nuvio.tv.music

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem

@Composable
internal fun MusicHomeScreen(
    state: MusicHomeState,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    onHeroDetails: (ShelfItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.loading && state.shelves.isEmpty() -> MusicLoading(modifier.fillMaxSize())
        state.error != null && state.shelves.isEmpty() ->
            MusicErrorState(state.error, onRetry, modifier)
        state.shelves.isEmpty() -> MusicEmptyState(
            title = "No music yet",
            message = "The music service didn't send anything back. Check the connection and try again.",
            modifier = modifier
        )
        else -> MusicHomeFeed(
            state = state,
            onShelfItemClick = onShelfItemClick,
            onHeroDetails = onHeroDetails,
            onLoadMore = onLoadMore,
            modifier = modifier
        )
    }
}

@Composable
private fun MusicHomeFeed(
    state: MusicHomeState,
    onShelfItemClick: (HomeShelf, Int) -> Unit,
    onHeroDetails: (ShelfItem) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.shelves.size) {
        if (nearEnd) onLoadMore()
    }

    val heroShelf = state.shelves.firstOrNull { it.items.isNotEmpty() }
    val heroItem = heroShelf?.items?.firstOrNull()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (heroShelf != null && heroItem != null) {
            item(key = "hero") {
                MusicHero(
                    shelf = heroShelf,
                    item = heroItem,
                    onClick = { onHeroDetails(heroItem) },
                    onPlay = {
                        // A card that carries a playlist/mix id opens as that full queue;
                        // [onShelfItemClick] also plays a plain track card directly.
                        onShelfItemClick(heroShelf, 0)
                    }
                )
            }
        }
        itemsIndexed(
            items = state.shelves,
            key = { index, shelf -> "${shelf.title}#$index" }
        ) { _, shelf ->
            MusicShelfRow(
                shelf = shelf,
                onItemClick = { _, index -> onShelfItemClick(shelf, index) }
            )
        }
        if (state.loadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = MusicPalette.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicHero(
    shelf: HomeShelf,
    item: ShelfItem,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    val colors = musicAuroraColors(item.thumbnailUrl ?: item.title)
    val (first, second) = colors
    val shelfSubtitle = shelf.subtitle.withoutMusicServiceBrand()
    val heroSubtitle = item.subtitle.withoutMusicServiceBrand()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        first.copy(alpha = 0.34f),
                        MusicPalette.surface,
                        MusicPalette.background
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(19.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(21.dp)
        ) {
            // The mockup splits the hero 1.02fr / 0.98fr, with the record centred in the
            // artwork column instead of hugging the left edge.
            Box(
                Modifier.weight(1.02f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(212.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MusicVinyl(
                        colors = colors,
                        modifier = Modifier
                            .size(187.dp)
                            .offset(x = 55.dp, y = 7.dp),
                        spinning = true
                    )
                    MusicArtwork(
                        url = item.thumbnailUrl,
                        modifier = Modifier.size(212.dp),
                        cornerRadius = 14.dp,
                        iconSize = 52.dp,
                        artworkPx = 1080
                    )
                }
            }
            Column(Modifier.weight(0.98f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MusicTag(
                        text = if (item.isVideo) "Video" else "Featured",
                        color = MusicPalette.accentTeal
                    )
                    if (shelfSubtitle.isNotBlank()) {
                        MusicTag(shelfSubtitle)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    text = item.title,
                    color = MusicPalette.text,
                    fontFamily = MusicDisplay,
                    fontSize = 44.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (heroSubtitle.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = heroSubtitle,
                        color = MusicPalette.textSecondary,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(19.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroAction(
                        icon = Icons.Default.PlayArrow,
                        label = if (item.videoId != null) "Play" else "Open",
                        primary = true,
                        onClick = onPlay
                    )
                    if (item.browseId != null) {
                        HeroAction(
                            icon = Icons.Default.ChevronRight,
                            label = "Details",
                            primary = false,
                            onClick = onClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroAction(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val background = when {
        focused -> MusicPalette.accent
        primary -> MusicPalette.text
        else -> Color.Transparent
    }
    val contentColor = when {
        focused -> MusicPalette.background
        primary -> MusicPalette.background
        else -> MusicPalette.text
    }
    Row(
        modifier
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.18f),
                shape
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
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
