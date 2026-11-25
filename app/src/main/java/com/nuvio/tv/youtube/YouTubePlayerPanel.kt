package com.nuvio.tv.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.nuvio.tv.R

private enum class PanelMode { HOME, MAIN, PLAYLISTS, COMMENTS, REPLIES, RELATED }

private val PanelBg = Color(0xF2111114)
private val RowBg = Color(0xFF1C1C1E)
private val Accent = Color(0xFFFF0033)

/** One row in the panel's lists. */
private class PanelEntry(
    val title: String,
    val detail: String?,
    val titleLines: Int = 2,
    val thumbnail: String? = null,
    val extraDetail: String? = null,
    /** Holding OK on the row, when it has something to do for that. */
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit = {}
)

/**
 * The YouTube panel over the player: what you can do about the video that's playing. Opened from the
 * YouTube button in the controls; Back goes up a level, then closes it.
 */
@Composable
fun BoxScope.YouTubePlayerPanel(
    videoId: String,
    viewModel: YouTubePlayerViewModel,
    onClose: () -> Unit,
    onOpenChannel: (channelId: String, name: String) -> Unit,
    onPlayVideo: (YtItem) -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    /** Opened with Down: show chapters and recommendations first, not the action list. */
    startWithRecommendations: Boolean = false,
    /** Opened by the player's Comments button: show the comments list first. */
    startInComments: Boolean = false,
    /** 0 = video at full screen, 1 = recommendations fill the screen. */
    scrollFraction: Float = 0f,
    onScrollFractionChange: (Float) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var mode by remember(videoId, startWithRecommendations, startInComments) {
        mutableStateOf(
            when {
                startInComments -> PanelMode.COMMENTS
                startWithRecommendations -> PanelMode.HOME
                else -> PanelMode.MAIN
            }
        )
    }
    // Where you were in the comments, so coming back from a comment's replies lands on it.
    var commentsIndex by remember { mutableIntStateOf(0) }
    var replyingTo by remember { mutableStateOf<YtComment?>(null) }

    LaunchedEffect(videoId) {
        viewModel.load(videoId)
        if (startInComments) viewModel.loadComments()
        if (com.nuvio.tv.ui.screens.player.YouTubePageScrollUi.ENABLED) {
            viewModel.loadRelated()
        }
    }
    // One Back press moves one level: replies → comments → the panel's main page → closed.
    val goBack: () -> Unit = {
        when (mode) {
            PanelMode.REPLIES -> {
                viewModel.clearReplies()
                mode = PanelMode.COMMENTS
            }
            PanelMode.COMMENTS, PanelMode.PLAYLISTS, PanelMode.RELATED -> mode = PanelMode.MAIN
            PanelMode.HOME -> onClose()
            PanelMode.MAIN -> onClose()
        }
    }
    BackHandler { goBack() }

    val panelLayout = if (mode == PanelMode.HOME) {
        // Transparent: the bottom sheet carries its own background, so the video shows above it.
        Modifier.fillMaxSize()
    } else {
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp)
    }
    Box(
        panelLayout
            .then(if (mode == PanelMode.HOME) Modifier else Modifier.background(PanelBg))
            // Taking Back on the panel itself, before the activity's back dispatcher, is what makes
            // one press move one level while the panel has the remote.
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back && e.key != Key.Escape) return@onPreviewKeyEvent false
                // Move one level on the press, and swallow the release too so the activity's back
                // callback can't count the same press a second time.
                if (e.type == KeyEventType.KeyDown) goBack()
                true
            }
    ) {
        if (mode == PanelMode.HOME) {
            val info = state.info
            // One fluid move: the page rises from below while the video and its controls scroll up.
            var started by remember { mutableStateOf(false) }
            val pageScroll by animateFloatAsState(
                targetValue = if (started) scrollFraction else 0f,
                animationSpec = tween(460),
                label = "youtube-page-scroll"
            )
            val rootFocus = remember { FocusRequester() }
            val firstItemFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { rootFocus.requestFocus() }
                started = true
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = (1f - pageScroll) * size.height }
                    .focusRequester(rootFocus)
                    .focusable()
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF111114))
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    RecommendationsContent(
                        title = info?.title.orEmpty(),
                        chapters = info?.chapters.orEmpty(),
                        related = state.related,
                        loading = state.loading || state.relatedLoading,
                        firstItemFocusRequester = firstItemFocus,
                        firstItemUpFocusRequester = null,
                        onSeekTo = { startMs ->
                            onSeekTo(startMs)
                            onClose()
                        },
                        onOpenChannel = { item ->
                            item.channelId?.let { onOpenChannel(it, item.title) }
                        },
                        onPlayVideo = onPlayVideo,
                        onOpenActions = { mode = PanelMode.MAIN }
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val info = state.info

            // The heading for this level. The list below it is always there, so it always owns the remote.
            when (mode) {
                PanelMode.HOME -> Text(stringResource(R.string.onetube_chapters_recommendations), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                PanelMode.MAIN -> {
                    Text(
                        info?.title ?: "OneTube",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    info?.let {
                        Text(
                            listOfNotNull(it.author.takeIf(String::isNotBlank), it.subscribers).joinToString("  ·  "),
                            color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                PanelMode.PLAYLISTS -> Text(stringResource(R.string.onetube_save_to_playlist), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                PanelMode.COMMENTS -> Text(stringResource(R.string.onetube_comments), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                PanelMode.RELATED -> Text(stringResource(R.string.onetube_related_videos), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                PanelMode.REPLIES -> {
                    Text(stringResource(R.string.onetube_replies), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    replyingTo?.let {
                        Text(
                            "${it.author}: ${it.text}",
                            color = Color.Gray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val busy = when (mode) {
                PanelMode.HOME -> state.loading && info == null || state.relatedLoading && state.related.isEmpty()
                PanelMode.MAIN -> state.loading && info == null
                PanelMode.PLAYLISTS -> state.playlists == null
                PanelMode.COMMENTS -> state.commentsLoading && state.comments.isEmpty()
                PanelMode.REPLIES -> state.repliesLoading && state.replies.isEmpty()
                PanelMode.RELATED -> state.relatedLoading && state.related.isEmpty()
            }
            if (busy) {
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }

            val entries: List<PanelEntry> = when (mode) {
                PanelMode.HOME -> buildList {
                    val chapters = info?.chapters.orEmpty()
                    if (chapters.isEmpty() && state.related.isEmpty() && !state.relatedLoading) {
                        add(PanelEntry(stringResource(R.string.onetube_no_chapters), null))
                    }
                    if (chapters.isNotEmpty()) {
                        add(PanelEntry(stringResource(R.string.onetube_chapters_label), null))
                        chapters.forEach { (title, startMs) ->
                            add(PanelEntry(title, formatChapterTime(startMs)) {
                                onSeekTo(startMs)
                                onClose()
                            })
                        }
                    }
                    add(PanelEntry(stringResource(R.string.onetube_related_videos), null))
                    state.related.forEach { item ->
                        add(PanelEntry(
                            title = item.title,
                            detail = item.author,
                            extraDetail = listOfNotNull(item.meta, item.postTime, item.badge).distinct().joinToString("  ·  ").takeIf { it.isNotBlank() },
                            thumbnail = item.thumbnail,
                            onClick = { onPlayVideo(item) }
                        ))
                    }
                    add(PanelEntry(stringResource(R.string.onetube_video_actions), null) { mode = PanelMode.MAIN })
                }
                PanelMode.MAIN -> if (info == null) emptyList() else buildList {
                    add(PanelEntry(if (info.liked) stringResource(R.string.onetube_liked) else stringResource(R.string.onetube_like), info.likeCount) { viewModel.toggleLike() })
                    add(PanelEntry(if (info.disliked) stringResource(R.string.onetube_disliked) else stringResource(R.string.onetube_dislike), null) { viewModel.toggleDislike() })
                    add(PanelEntry(if (info.subscribed) stringResource(R.string.onetube_subscribed) else stringResource(R.string.onetube_subscribe), info.author.takeIf(String::isNotBlank)) {
                        viewModel.toggleSubscribe()
                    })
                    add(PanelEntry(stringResource(R.string.onetube_save_to_playlist), null) {
                        mode = PanelMode.PLAYLISTS
                        viewModel.loadPlaylists()
                    })
                    add(PanelEntry(stringResource(R.string.onetube_comments), null) {
                        commentsIndex = 0
                        mode = PanelMode.COMMENTS
                        viewModel.loadComments()
                    })
                    add(PanelEntry(stringResource(R.string.onetube_related_videos), null) {
                        mode = PanelMode.RELATED
                        viewModel.loadRelated()
                    })
                    info.channelId?.let { channel ->
                        add(PanelEntry(stringResource(R.string.onetube_open_channel), info.author.takeIf(String::isNotBlank)) { onOpenChannel(channel, info.author) })
                        add(PanelEntry(stringResource(R.string.onetube_hide_channel), stringResource(R.string.onetube_hide_channel_sub)) { viewModel.hideChannel() })
                    }
                }
                PanelMode.PLAYLISTS -> state.playlists.orEmpty().map { row ->
                    PanelEntry(if (row.contains) "${row.title}   ✓" else row.title, null) { viewModel.togglePlaylist(row) }
                }
                PanelMode.COMMENTS -> state.comments.map { c ->
                    PanelEntry(
                        title = c.text,
                        detail = listOfNotNull(
                            "Liked  ✓".takeIf { c.liked },
                            c.author, c.published, c.likes?.let { "$it likes" },
                            c.replyCount?.let { if (c.replyKey == null) null else if (it.any { ch -> ch.isLetter() }) it else "$it replies" }
                        ).joinToString("  ·  "),
                        titleLines = 6,
                        // Hold OK to like a comment (or take the like back), the way SmartTube does.
                        onLongClick = { viewModel.toggleCommentLike(c) },
                        onClick = {
                            // A comment with replies opens them; the list keeps its place for the way back.
                            val key = c.replyKey
                            if (key != null) {
                                replyingTo = c
                                mode = PanelMode.REPLIES
                                viewModel.loadReplies(key)
                            }
                        }
                    )
                }
                PanelMode.REPLIES -> state.replies.map { c ->
                    PanelEntry(
                        title = c.text,
                        detail = listOfNotNull(c.author, c.published, c.likes?.let { "$it likes" }).joinToString("  ·  "),
                        titleLines = 6
                    )
                }
                PanelMode.RELATED -> state.related.map { item ->
                    PanelEntry(
                        title = item.title,
                        detail = item.author,
                        extraDetail = listOfNotNull(item.meta, item.postTime, item.badge).distinct().joinToString("  ·  ").takeIf { it.isNotBlank() },
                        titleLines = 2,
                        thumbnail = item.thumbnail,
                        onClick = {
                            if (item.kind == YtKind.CHANNEL) {
                                item.channelId?.let { onOpenChannel(it, item.title) }
                            } else {
                                onPlayVideo(item)
                            }
                        }
                    )
                }
            }

            if (!busy && entries.isEmpty()) {
                Text(
                    when (mode) {
                        PanelMode.HOME -> if (state.relatedLoading) stringResource(R.string.onetube_loading_recommendations) else stringResource(R.string.onetube_no_chapters)
                        PanelMode.MAIN -> stringResource(R.string.onetube_details_failed)
                        PanelMode.PLAYLISTS -> stringResource(R.string.onetube_no_playlists)
                        PanelMode.COMMENTS -> stringResource(R.string.onetube_no_comments)
                        PanelMode.REPLIES -> stringResource(R.string.onetube_no_replies)
                        PanelMode.RELATED -> stringResource(R.string.onetube_no_related)
                    },
                    color = Color.Gray, fontSize = 14.sp
                )
            }

            // A fresh list for each level; the comments list starts where you left it.
            key(mode) {
                NavigableList(
                    entries = entries,
                    modifier = Modifier.weight(1f),
                    startAt = if (mode == PanelMode.COMMENTS) commentsIndex else 0,
                    onSelectedChange = { if (mode == PanelMode.COMMENTS) commentsIndex = it },
                    header = if (mode == PanelMode.COMMENTS) {
                        { focused -> SortBar(sort = state.commentsSort, focused = focused) }
                    } else null,
                    onHeaderStep = { delta ->
                        val next = (state.commentsSort + delta).coerceIn(0, 1)
                        if (next != state.commentsSort) {
                            commentsIndex = 0
                            viewModel.loadComments(newest = next == 1)
                        }
                    },
                    onNearEnd = {
                        when (mode) {
                            PanelMode.COMMENTS -> if (state.canLoadMoreComments) viewModel.loadMoreComments()
                            PanelMode.REPLIES -> if (state.canLoadMoreReplies) viewModel.loadMoreReplies()
                            else -> Unit
                        }
                    }
                )
            }

            state.message?.let {
                Text(it, color = Color(0xFFFFC107), fontSize = 13.sp)
            }
            if (mode == PanelMode.MAIN && !state.signedIn && info != null) {
                Text(stringResource(R.string.onetube_sign_in_actions), color = Color.Gray, fontSize = 12.sp)
            }
            if (mode == PanelMode.COMMENTS && entries.isNotEmpty()) {
                Text(
                    if (state.signedIn) stringResource(R.string.onetube_hold_ok_like) else stringResource(R.string.onetube_sign_in_like_comments),
                    color = Color.Gray, fontSize = 12.sp
                )
            }
            }
        }
    }
}

@Composable
private fun RecommendationsContent(
    title: String,
    chapters: List<Pair<String, Long>>,
    related: List<YtItem>,
    loading: Boolean,
    firstItemFocusRequester: FocusRequester,
    firstItemUpFocusRequester: FocusRequester? = null,
    onSeekTo: (Long) -> Unit,
    onOpenChannel: (YtItem) -> Unit,
    onPlayVideo: (YtItem) -> Unit,
    onOpenActions: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title.ifBlank { stringResource(R.string.onetube_up_next) },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(stringResource(R.string.onetube_back_return), color = Color.Gray, fontSize = 12.sp)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (chapters.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.onetube_chapters), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(chapters.size) { index ->
                            val (chapterTitle, startMs) = chapters[index]
                            RecommendationCard(
                                title = chapterTitle,
                                detail = formatChapterTime(startMs),
                                thumbnail = null,
                                focusRequester = if (index == 0) firstItemFocusRequester else null,
                                upFocusRequester = if (index == 0) firstItemUpFocusRequester else null,
                                onClick = { onSeekTo(startMs) }
                            )
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.onetube_related_suggestions), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (related.isEmpty() && loading) {
                item { Text(stringResource(R.string.onetube_loading_recommendations), color = Color.Gray, fontSize = 13.sp) }
            } else if (related.isEmpty()) {
                item { Text(stringResource(R.string.onetube_no_recommendations), color = Color.Gray, fontSize = 13.sp) }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(related.size) { index ->
                            val item = related[index]
                            val focusRequester = if (chapters.isEmpty() && index == 0) firstItemFocusRequester else null
                            val upFocusRequester = if (chapters.isEmpty() && index == 0) firstItemUpFocusRequester else null
                            if (item.kind == YtKind.CHANNEL) {
                                ChannelRecommendationCard(
                                    channel = item,
                                    focusRequester = focusRequester,
                                    upFocusRequester = upFocusRequester,
                                    onClick = { onOpenChannel(item) }
                                )
                            } else {
                                RecommendationCard(
                                    title = item.title,
                                    detail = listOfNotNull(item.author, item.badge).joinToString(" · "),
                                    thumbnail = item.thumbnail,
                                    focusRequester = focusRequester,
                                    upFocusRequester = upFocusRequester,
                                    onClick = { onPlayVideo(item) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(RowBg)
                        .border(2.dp, Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenActions)
                        .focusable()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.onetube_video_actions), color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    title: String,
    detail: String,
    thumbnail: String?,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Column(
        modifier = Modifier
            .then(focusModifier)
            .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
            .width(220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RowBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(Color(0xFF303034))
        ) {
            if (!thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (detail.matches(Regex("\\d+:\\d{2}(:\\d{2})?"))) {
                Text(
                    detail,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank() && !detail.matches(Regex("\\d+:\\d{2}(:\\d{2})?"))) {
                Text(detail, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChannelRecommendationCard(
    channel: YtItem,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Column(
        modifier = Modifier
            .then(focusModifier)
            .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
            .width(220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(RowBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(Color(0xFF303034))
        ) {
            if (!channel.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = channel.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                stringResource(R.string.onetube_channel_tag),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(channel.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = channel.author ?: channel.meta
            if (!detail.isNullOrBlank()) {
                Text(detail, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun formatChapterTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** The "Top / Newest" choice above the comments, like the one on the YouTube page. */
@Composable
private fun SortBar(sort: Int, focused: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.onetube_sort_by), color = Color.Gray, fontSize = 13.sp)
        listOf(stringResource(R.string.onetube_top), stringResource(R.string.onetube_newest)).forEachIndexed { index, label ->
            Text(
                label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (index == sort) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (index == sort) Accent else RowBg)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        if (focused) Text(stringResource(R.string.onetube_switch_hint), color = Color.Gray, fontSize = 11.sp)
    }
}

/**
 * A list that handles the remote itself. Up and Down move a highlight and scroll it into view, OK presses
 * the highlighted row, and Left and Right do nothing. Focus never leaves the list, so scrolling a long list
 * can't drop you back onto the player's controls; Back is how you leave.
 *
 * With a [header], Up from the first row moves onto it; there, Left and Right call [onHeaderStep] and Down
 * goes back to the list.
 */
@Composable
private fun NavigableList(
    entries: List<PanelEntry>,
    modifier: Modifier = Modifier,
    startAt: Int = 0,
    onSelectedChange: (Int) -> Unit = {},
    header: (@Composable (focused: Boolean) -> Unit)? = null,
    onHeaderStep: (Int) -> Unit = {},
    onNearEnd: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var selected by remember { mutableIntStateOf(startAt.coerceAtLeast(0)) }
    var onHeader by remember { mutableStateOf(false) }
    // OK is held down on a row (and whether that already counted as a long press).
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }

    // This list keeps the remote for as long as it's showing.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A list that gets shorter can't leave the highlight past its end.
    LaunchedEffect(entries.size) {
        if (selected > entries.lastIndex) selected = entries.lastIndex.coerceAtLeast(0)
    }
    LaunchedEffect(selected) { onSelectedChange(selected) }
    // Bring the highlighted row into view, only when it isn't already fully visible.
    LaunchedEffect(selected, entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == selected }
        val fullyVisible = row != null && row.offset >= 0 && row.offset + row.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(selected)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                // OK presses the row when it's let go; holding it (long press) does the row's other action, if
                // it has one. A release with no press seen here (the press opened this list) is ignored.
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                        } else if (pressed && !longFired && !onHeader) {
                            entries.getOrNull(selected)?.onLongClick?.let { onLong ->
                                longFired = true
                                onLong()
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (pressed && !longFired && !onHeader) entries.getOrNull(selected)?.onClick?.invoke()
                        pressed = false
                        longFired = false
                    }
                    return@onPreviewKeyEvent true
                }
                val handled = when (e.key) {
                    Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
                if (handled && e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionDown -> {
                            if (onHeader) {
                                onHeader = false
                            } else {
                                if (selected < entries.lastIndex) selected += 1
                                if (selected >= entries.lastIndex - 2) onNearEnd()
                            }
                        }
                        Key.DirectionUp -> {
                            if (!onHeader) {
                                if (selected > 0) selected -= 1 else if (header != null) onHeader = true
                            }
                        }
                        Key.DirectionLeft -> if (onHeader) onHeaderStep(-1)
                        Key.DirectionRight -> if (onHeader) onHeaderStep(1)
                        else -> Unit
                    }
                }
                handled
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        header?.invoke(onHeader)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(entries) { index, entry ->
                val isSelected = index == selected && !onHeader
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color(0xFF2A2A2E) else RowBg)
                        .border(2.dp, if (isSelected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!entry.thumbnail.isNullOrBlank()) {
                        AsyncImage(
                            model = entry.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(124.dp)
                                .height(70.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF303034))
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = if (entry.thumbnail.isNullOrBlank()) 6.dp else 0.dp, vertical = 3.dp)
                    ) {
                        Text(entry.title, color = Color.White, fontSize = 15.sp, maxLines = entry.titleLines, overflow = TextOverflow.Ellipsis)
                        if (!entry.detail.isNullOrBlank()) {
                            Text(entry.detail, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!entry.extraDetail.isNullOrBlank()) {
                            Text(entry.extraDetail, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
