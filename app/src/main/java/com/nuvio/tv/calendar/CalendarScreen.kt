package com.nuvio.tv.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class CalTab { UPCOMING, RECENT, AIRING, FOLLOWING, HIDDEN }
private enum class CalKind { ALL, SHOWS, MOVIES }
private enum class AirFilter { ALL, PRIME, STREAMING, FOLLOWING }

private val CARD_W = 208.dp
/** Holding Up/Down in the List view moves one row this often (ms). */
private const val HELD_ROW_MS = 180L
private const val HERO_H = 212

/** Nuvio's card focus: the theme's focus ring, 2 dp, and a 1.02 scale (same as ContentCard). */
private const val FOCUS_SCALE = 1.02f
private val RING_W = 2.dp

/** The theme's focus ring around [shape], only while [focused]. */
@Composable
private fun Modifier.focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val ring = NuvioTheme.focusRing.border(RING_W)
    return if (focused) this.border(ring, shape) else this
}

/** Called when the card Back returns to (the first one, or today) gets the focus. */
private val LocalAtHome = staticCompositionLocalOf<() -> Unit> { {} }

/** Marks the card Back returns to. */
@Composable
private fun Modifier.homeMark(): Modifier {
    val mark = LocalAtHome.current
    return this.onFocusChanged { if (it.isFocused) mark() }
}

/**
 * The card the highlight is on, so coming back to the Calendar (from a show page or player)
 * puts it back on that card instead of the first one.
 */
private class CardFocus(val requester: (String) -> FocusRequester, val onFocused: (String) -> Unit)
private val LocalCardFocus = staticCompositionLocalOf<CardFocus?> { null }

/** Marks a card (or a day in the month) by [key], so the highlight can come back to it. */
@Composable
private fun Modifier.cardFocus(key: String): Modifier {
    val cf = LocalCardFocus.current ?: return this
    return this.focusRequester(cf.requester(key)).onFocusChanged { if (it.isFocused) cf.onFocused(key) }
}

/**
 * Focus can't leave this row ([right]) or column ([down]) that way: at the end of a row, Right
 * stays put instead of jumping to a card in another row (or up to the buttons).
 */
private fun Modifier.keepFocusIn(right: Boolean = false, down: Boolean = false): Modifier =
    this.focusProperties {
        onExit = {
            val d = requestedFocusDirection
            if ((right && d == FocusDirection.Right) || (down && d == FocusDirection.Down)) cancelFocusChange()
        }
    }.focusGroup()

/** Calendar card keys for a day in the month view. */
private fun dayKey(day: LocalDate) = "day|$day"

/**
 * The Calendar: your shows' and movies' release dates as rows of big cards by day (or a week,
 * or a month), what's airing today, and the shows you follow. The highlighted entry fills the
 * top, like Nuvio's Home.
 */
@Composable
fun CalendarScreen(
    /** Opens a show's or movie's page (at the episode, when there is one). */
    onOpenDetail: (id: String, type: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit,
    /** Finds sources for an episode that's out. */
    onFindSources: (CalendarEntry) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(CalTab.UPCOMING) }
    var kind by rememberSaveable { mutableStateOf(CalKind.ALL) }
    var airFilter by rememberSaveable { mutableStateOf(AirFilter.ALL) }
    val view = settings.calendarView.takeIf { it == "WEEK" || it == "MONTH" } ?: "LIST"
    val grid = view != "LIST"
    // Kept when you leave (a show page) so the same entry is up top when you come back.
    var heroKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The card (or month day) the highlight was last on: coming back puts it there again.
    var lastCard by rememberSaveable { mutableStateOf<String?>(null) }
    val cardRequesters = remember { HashMap<String, FocusRequester>() }
    val focusedCard = remember { arrayOfNulls<String>(1) }
    val cardFocuses = remember {
        CardFocus(
            requester = { k -> cardRequesters.getOrPut(k) { FocusRequester() } },
            onFocused = { k -> lastCard = k; focusedCard[0] = k }
        )
    }
    // The header's buttons: Right at the end of the filters goes to List / Week / Month (or
    // Refresh), and Down from those comes back to the end of the filters.
    val viewFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val filterEnd = remember { FocusRequester() }
    // What the highlight was on when this screen opened (null: a fresh start).
    val restoreKey = remember { lastCard }
    var options by remember { mutableStateOf<CalendarEntry?>(null) }
    var followOptions by remember { mutableStateOf<CalendarFollow?>(null) }
    val firstItem = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Back: deep in the cards, back to the first one (or today); from there, the side menu.
    var resetKey by remember { mutableStateOf(0) }
    var atHome by remember { mutableStateOf(true) }
    var inContent by remember { mutableStateOf(false) }
    // Anything on this page has the highlight (false: it was lost, e.g. its card went away).
    var screenFocused by remember { mutableStateOf(false) }
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarOpen = com.nuvio.tv.LocalSidebarExpanded.current
    val sidebarOpenNow by rememberUpdatedState(sidebarOpen)
    BackHandler(enabled = !sidebarOpen) {
        if (inContent && !atHome) { resetKey++; atHome = true } else openSidebar()
    }

    LaunchedEffect(tab) { if (tab == CalTab.AIRING) viewModel.loadAiring() }

    val today = LocalDate.now()
    val all = state.entries
    val filtered = all.filter {
        when (kind) {
            CalKind.ALL -> true
            CalKind.SHOWS -> it.kind == CalendarKind.EPISODE
            CalKind.MOVIES -> it.kind == CalendarKind.MOVIE
        }
    }
    val upcoming = filtered.filter { !it.day.isBefore(today) && (it.day == today || whenOf(it) + (it.runtimeMin ?: 30) * 60_000L > now) }
    val recent = filtered.filter { !it.day.isAfter(today) && whenOf(it) <= now && !(settings.calendarHideWatched && it.watched) }
        .sortedWith(compareByDescending<CalendarEntry> { it.day }.thenByDescending { it.airMs ?: 0L })
    val airingShown = airing.entries.filter {
        when (airFilter) {
            AirFilter.ALL -> true
            AirFilter.PRIME -> it.airMs?.let { ms -> Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour in 19..22 } == true
            AirFilter.STREAMING -> it.streaming
            AirFilter.FOLLOWING -> it.followed || follows.any { f -> f.id == it.contentId || f.imdbId == it.contentId }
        }
    }
    val pool = when (tab) {
        CalTab.UPCOMING -> upcoming
        CalTab.RECENT -> recent
        CalTab.AIRING -> airingShown
        else -> emptyList()
    }
    // Following: each followed show up top as its episode on now, else its next one, else the
    // last one aired. With nothing at all, a plain card ("follow|…" key) with a line about it.
    fun followEntry(f: CalendarFollow): CalendarEntry {
        val ids = setOf(f.id, f.imdbId ?: f.id)
        val mine = all.filter { it.contentId in ids || it.key == f.id }
        mine.firstOrNull { whenOf(it) <= now && whenOf(it) + (it.runtimeMin ?: 30) * 60_000L > now }?.let { return it }
        mine.firstOrNull { whenOf(it) > now }?.let { return it }
        if (!f.isAiring) mine.lastOrNull { whenOf(it) <= now }?.let { return it }
        val note = when {
            f.type == "movie" -> "Waiting to come out"
            else -> "Nothing announced yet"
        }
        return CalendarEntry(
            key = "follow|" + f.id,
            kind = if (f.type == "movie") CalendarKind.MOVIE else CalendarKind.EPISODE,
            contentId = f.imdbId ?: f.id,
            type = if (f.type == "movie") "movie" else "series",
            title = f.name,
            poster = f.poster,
            background = f.background,
            day = today,
            note = note
        )
    }
    fun isPlainFollow(e: CalendarEntry) = e.key.startsWith("follow|")
    val followRows = if (tab == CalTab.FOLLOWING) follows.distinctBy { it.id }.map { it to followEntry(it) } else emptyList()
    // The entry shown big at the top: the one highlighted, else the next thing coming.
    val hero = if (tab == CalTab.FOLLOWING) (followRows.firstOrNull { "follow|" + it.first.id == heroKey } ?: followRows.firstOrNull())?.second
        else pool.firstOrNull { it.key == heroKey } ?: pool.firstOrNull()

    fun isFollowed(e: CalendarEntry) = follows.any { it.id == e.contentId || it.imdbId == e.contentId || it.id == e.key }
    fun page(e: CalendarEntry) {
        if (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")) return
        val today2 = e.key.startsWith("today|")
        onOpenDetail(e.contentId, e.type, e.addonBaseUrl, if (today2) null else e.season, if (today2) null else e.episode)
    }
    fun open(e: CalendarEntry) {
        page(e)
    }
    /**
     * The highlighted card is about to go away (hidden, taken off, bell off): once it's gone, the
     * highlight goes to the card next to it (else the first card, else the tabs) instead of being
     * lost, which left the remote doing nothing until a press picked something at random.
     */
    fun focusAfterRemoving(neighbour: String?) {
        scope.launch {
            repeat(12) {
                delay(150)
                if (sidebarOpenNow) return@launch
                if (screenFocused) return@repeat
                if (neighbour != null && runCatching { cardFocuses.requester(neighbour).requestFocus() }.isSuccess) return@launch
                if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
                return@launch
            }
        }
    }
    /** The card next to [e] that stays when its whole show goes (the next one, else the one before). */
    fun neighbourOf(e: CalendarEntry): String? {
        val list = (if (grid || tab == CalTab.AIRING) (if (tab == CalTab.AIRING) airingShown else filtered) else pool).distinctBy { it.key }
        val i = list.indexOfFirst { it.key == e.key }
        if (i < 0) return null
        return (list.drop(i + 1) + list.take(i).reversed()).firstOrNull { it.contentId != e.contentId }?.key
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).onFocusChanged { screenFocused = it.hasFocus }) {
        val showHero = when (tab) {
            CalTab.UPCOMING, CalTab.RECENT -> !grid
            CalTab.AIRING -> true
            CalTab.FOLLOWING -> follows.isNotEmpty()
            else -> false
        }
        if (showHero) HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- the big picture
            if (showHero) {
                Hero(
                    e = hero,
                    now = now,
                    use24h = use24h,
                    loading = (state.loading && state.entries.isEmpty()) || (tab == CalTab.AIRING && airing.loading && airing.entries.isEmpty())
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.padding(start = 64.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(
                        when (tab) { CalTab.FOLLOWING -> "Following"; CalTab.HIDDEN -> "Hidden shows"; else -> "Calendar" },
                        size = 28.sp, weight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(14.dp))
                    LiveText(
                        when {
                            tab == CalTab.FOLLOWING -> "${follows.distinctBy { it.id }.size} with the bell on"
                            tab == CalTab.HIDDEN -> "Left off your Calendar"
                            state.loading -> state.progress?.let { (a, b) -> "Looking up your shows… $a of $b" } ?: "Looking up your shows…"
                            else -> "Times in your time zone"
                        },
                        size = 13.sp, color = NuvioTheme.colors.TextSecondary
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // ---------------------------------------------------------------- where you are: tabs, view, refresh
            Row(
                Modifier.padding(start = 56.dp, end = 32.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                fun tabMod(on: Boolean) = if (on) Modifier.focusRequester(tabFocus) else Modifier
                if (grid) {
                    val mine = tab == CalTab.UPCOMING || tab == CalTab.RECENT
                    TabPill("My calendar", mine, tabMod(mine)) { tab = CalTab.UPCOMING }
                } else {
                    TabPill("Upcoming", tab == CalTab.UPCOMING, tabMod(tab == CalTab.UPCOMING)) { tab = CalTab.UPCOMING; heroKey = null }
                    TabPill("Recently aired", tab == CalTab.RECENT, tabMod(tab == CalTab.RECENT)) { tab = CalTab.RECENT; heroKey = null }
                }
                TabPill("Airing today", tab == CalTab.AIRING, tabMod(tab == CalTab.AIRING)) { tab = CalTab.AIRING; heroKey = null }
                TabPill("Following", tab == CalTab.FOLLOWING, tabMod(tab == CalTab.FOLLOWING)) { tab = CalTab.FOLLOWING }
                if (hidden.isNotEmpty() || tab == CalTab.HIDDEN) TabPill("Hidden", tab == CalTab.HIDDEN, tabMod(tab == CalTab.HIDDEN)) { tab = CalTab.HIDDEN }
                Spacer(Modifier.weight(1f))
                val hasFilters = tab == CalTab.UPCOMING || tab == CalTab.RECENT || tab == CalTab.AIRING
                val downToFilters = if (hasFilters) Modifier.focusProperties { down = filterEnd } else Modifier
                if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
                    Segmented(
                        listOf("LIST" to "List", "WEEK" to "Week", "MONTH" to "Month"), view,
                        selectedModifier = Modifier.focusRequester(viewFocus),
                        modifier = downToFilters
                    ) { v ->
                        viewModel.setView(v); heroKey = null
                        if (v != "LIST") tab = CalTab.UPCOMING
                    }
                    Spacer(Modifier.width(6.dp))
                }
                IconPill(Modifier.focusRequester(refreshFocus).then(downToFilters).focusProperties { right = FocusRequester.Cancel }) {
                    if (tab == CalTab.AIRING) viewModel.loadAiring(force = true) else viewModel.refresh()
                }
            }
            // ---------------------------------------------------------------- what you see: filters (quieter)
            if (tab == CalTab.UPCOMING || tab == CalTab.RECENT || tab == CalTab.AIRING) {
                Row(
                    Modifier.padding(start = 64.dp, end = 32.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val endMod = Modifier.focusRequester(filterEnd).focusProperties {
                        right = if (tab == CalTab.AIRING) refreshFocus else viewFocus
                    }
                    when (tab) {
                        CalTab.UPCOMING, CalTab.RECENT -> {
                            val switchShown = tab == CalTab.RECENT && !grid
                            Pill("All", kind == CalKind.ALL, quiet = true) { kind = CalKind.ALL; heroKey = null }
                            Pill("Shows", kind == CalKind.SHOWS, quiet = true) { kind = CalKind.SHOWS; heroKey = null }
                            Pill("Movies", kind == CalKind.MOVIES, if (switchShown) Modifier else endMod, quiet = true) { kind = CalKind.MOVIES; heroKey = null }
                            if (switchShown) {
                                Divider()
                                SwitchPill("Unwatched only", settings.calendarHideWatched, endMod) { viewModel.setHideWatched(!settings.calendarHideWatched); heroKey = null }
                            }
                        }
                        CalTab.AIRING -> {
                            Pill("All", airFilter == AirFilter.ALL, quiet = true) { airFilter = AirFilter.ALL; heroKey = null }
                            Pill("Prime time", airFilter == AirFilter.PRIME, quiet = true) { airFilter = AirFilter.PRIME; heroKey = null }
                            Pill("Streaming", airFilter == AirFilter.STREAMING, quiet = true) { airFilter = AirFilter.STREAMING; heroKey = null }
                            Pill("Following", airFilter == AirFilter.FOLLOWING, quiet = true) { airFilter = AirFilter.FOLLOWING; heroKey = null }
                            Divider()
                            SwitchPill("News & talk shows", !settings.calendarSkipNewsTalk, endMod) { viewModel.setSkipNewsTalk(!settings.calendarSkipNewsTalk) }
                        }
                        else -> Unit
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            // ---------------------------------------------------------------- what's on
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onFocusChanged { inContent = it.hasFocus }
                    .onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown) {
                            when (k.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> atHome = false
                            }
                        }
                        false
                    }
            ) {
                CompositionLocalProvider(LocalAtHome provides { atHome = true }, LocalCardFocus provides cardFocuses) {
                    val onFocus: (CalendarEntry) -> Unit = { heroKey = it.key }
                    when (tab) {
                        CalTab.UPCOMING, CalTab.RECENT -> when {
                            view == "MONTH" -> MonthView(
                                entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                                onOpen = ::open, onOptions = { options = it }
                            )
                            view == "WEEK" -> WeekView(
                                entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                                onOpen = ::open, onOptions = { options = it }
                            )
                            pool.isEmpty() && state.problem != null && state.entries.isEmpty() -> EmptyNote(
                                text = "Check your connection and try again.",
                                headline = "Couldn't load the calendar",
                                action = "Retry", actionFocus = firstItem, onAction = { viewModel.refresh() }
                            )
                            pool.isEmpty() -> {
                                val text = when {
                                    state.loading && state.entries.isEmpty() -> "Looking up your shows…"
                                    state.showCount == 0 -> "Nothing here yet. Shows you watch, your Library and shows you ring the bell on show up here with their new episodes."
                                    tab == CalTab.UPCOMING -> "Nothing coming up in the next four months."
                                    settings.calendarHideWatched -> "You're all caught up."
                                    else -> "Nothing aired in the last month."
                                }
                                val loadingNow = state.loading && state.entries.isEmpty()
                                if (loadingNow || tab == CalTab.RECENT) EmptyNote(text)
                                else EmptyNote(text, action = "See what's airing today", actionFocus = firstItem, onAction = { tab = CalTab.AIRING; heroKey = null })
                            }
                            else -> DayRows(
                                entries = pool,
                                newestFirst = tab == CalTab.RECENT,
                                use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                                onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                            )
                        }
                        CalTab.AIRING -> when {
                            airing.problem != null && airing.entries.isEmpty() -> EmptyNote(
                                text = airing.problem!!, headline = "Couldn't load today's shows",
                                action = "Retry", actionFocus = firstItem, onAction = { viewModel.loadAiring(force = true) }
                            )
                            airing.entries.isEmpty() ->
                                if (airing.loading) EmptyNote("Getting today's shows…")
                                else EmptyNote("No new episodes found for today.", action = "Refresh", actionFocus = firstItem, onAction = { viewModel.loadAiring(force = true) })
                            airingShown.isEmpty() -> EmptyNote("Nothing here with this filter.")
                            else -> PosterGrid(
                                entries = airingShown, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                                isFollowed = ::isFollowed,
                                onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                            )
                        }
                        CalTab.FOLLOWING -> {
                            if (follows.isEmpty()) EmptyNote(
                                "Ring the bell on a show's page or in Search / Discover (hold OK) to follow it.",
                                headline = "No bells rung yet",
                                action = "See what's airing today", actionFocus = firstItem, onAction = { tab = CalTab.AIRING; heroKey = null }
                            )
                            else FollowingGrid(
                                follows = follows.distinctBy { it.id }, entries = all, now = now, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                                onFocus = { f -> heroKey = "follow|" + f.id },
                                onOpen = { f ->
                                    if (!f.isAiring) onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                                    else f.imdbId?.let { onOpenDetail(it, "movie", null, null, null) }
                                },
                                onOptions = { followOptions = it }
                            )
                        }
                        CalTab.HIDDEN -> {
                            if (hidden.isEmpty()) EmptyNote("No hidden shows.")
                            else LazyColumn(
                                Modifier.padding(start = 64.dp, end = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(bottom = 30.dp)
                            ) {
                                val list = hidden.distinctBy { it.id }
                                items(list, key = { it.id }) { h ->
                                    LiveFocusRow(
                                        modifier = Modifier.fillMaxWidth().then(if (h == list.first()) Modifier.focusRequester(firstItem).homeMark() else Modifier),
                                        onClick = { viewModel.unhide(h); focusAfterRemoving(null) }
                                    ) { focused ->
                                        LiveText(h.name.ifBlank { h.id }, size = 17.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused), modifier = Modifier.weight(1f))
                                        LiveText("OK: show it on the Calendar again", size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Opening the Calendar: back on the card you were on (coming back from a show page or the
        // player), else straight onto the first card, else the tabs.
        LaunchedEffect(Unit) {
            delay(300)
            val back = restoreKey
            if (back != null) {
                atHome = false
                repeat(6) {
                    runCatching { cardFocuses.requester(back).requestFocus() }
                    delay(80)
                    if (focusedCard[0] == back) return@LaunchedEffect
                }
                atHome = true
            }
            if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
        }

        // Hold OK: options menu for an entry.
        options?.let { e ->
            val close = { options = null }
            val fromAiringToday = e.key.startsWith("today|")
            val airingFollow = e.key.startsWith("air|")
            val followed = isFollowed(e)
            val extra = buildList {
                if (e.kind == CalendarKind.EPISODE && e.videoId != null && whenOf(e) <= now) {
                    add(MenuAction("Find sources for this episode") { close(); onFindSources(e) })
                }
                if (e.contentId.startsWith("tt") || e.kind == CalendarKind.EPISODE) {
                    add(
                        MenuAction(
                            when {
                                e.kind == CalendarKind.MOVIE -> "Open movie page"
                                e.episode != null && !fromAiringToday -> "Open show page at this episode"
                                else -> "Open show page"
                            }
                        ) { close(); page(e) }
                    )
                }
                if (e.kind == CalendarKind.EPISODE) {
                    add(
                        MenuAction(
                            if (followed) "Turn off the bell" else "Ring the bell",
                            if (followed) null else "Follow it: its new episodes on Home and here"
                        ) { close(); viewModel.toggleFollow(e) }
                    )
                }
                when {
                    airingFollow -> add(MenuAction("Take it off your Calendar") { val n = neighbourOf(e); close(); viewModel.toggleFollow(e); focusAfterRemoving(n) })
                    fromAiringToday -> Unit
                    e.kind == CalendarKind.MOVIE && followed -> add(MenuAction("Turn off the bell") { val n = neighbourOf(e); close(); viewModel.toggleFollow(e); focusAfterRemoving(n) })
                    else -> add(
                        MenuAction(
                            "Hide this ${if (e.kind == CalendarKind.MOVIE) "movie" else "show"} from the Calendar",
                            "It comes back from Hidden"
                        ) { val n = neighbourOf(e); close(); viewModel.hide(e); focusAfterRemoving(n) }
                    )
                }
            }
            CalendarMenu(
                kicker = null,
                title = e.title,
                subtitle = entryLine(e, use24h),
                actions = extra,
                onDismiss = close
            )
        }
        followOptions?.let { f ->
            val close = { followOptions = null }
            val fe = followEntry(f).takeIf { !isPlainFollow(it) }
            CalendarMenu(
                kicker = null,
                title = f.name,
                subtitle = fe?.let { entryLine(it, use24h) },
                actions = buildList {
                    if (!f.isAiring || f.imdbId != null) add(
                        MenuAction("Open ${if (f.type == "series") "show" else "movie"} page") {
                            close()
                            if (f.isAiring) onOpenDetail(f.imdbId!!, "movie", null, null, null) else onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                        }
                    )
                    add(
                        MenuAction(if (f.isAiring) "Take it off your Calendar" else "Turn off the bell") {
                            val list = follows.distinctBy { it.id }
                            val i = list.indexOfFirst { it.id == f.id }
                            val n = (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.let { "follow|" + it.id }
                            close(); viewModel.toggleFollow(f); focusAfterRemoving(n)
                        }
                    )
                },
                onDismiss = close
            )
        }
    }
}

/** "S2 · E5 · Tonight 10:00 PM · FX". */
private fun entryLine(e: CalendarEntry, use24h: Boolean): String = listOfNotNull(
    e.episodeCode,
    dayWord(e.day, LocalDate.now()) + (e.airMs?.takeIf { !e.streaming }?.let { " " + formatClock(it, use24h) } ?: ""),
    e.network
).joinToString(" · ")

// -------------------------------------------------------------------- the big picture at the top

@Composable
private fun HeroBackdrop(e: CalendarEntry?) {
    val art = when {
        e == null -> null
        e.spoiler -> e.background
        else -> e.background ?: e.episodeImage
    }
    Box(Modifier.fillMaxWidth().height((HERO_H + 120).dp)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.18f to Color.Black.copy(alpha = 0.25f),
                            0.42f to Color.Black.copy(alpha = 0.75f), 0.62f to Color.Black
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 0.8f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            Crossfade(targetState = art, animationSpec = tween(350), label = "calendarHero") { url ->
                if (url != null) {
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(
    e: CalendarEntry?,
    now: Long,
    use24h: Boolean,
    loading: Boolean
) {
    Column(
        Modifier.fillMaxWidth().height(HERO_H.dp).padding(start = 64.dp, top = 26.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (e == null) {
            LiveText("Calendar", size = 30.sp, weight = FontWeight.ExtraBold)
            LiveText(if (loading) "Looking up your shows…" else "Nothing to show here yet", size = 15.sp, color = NuvioTheme.colors.TextSecondary)
            return@Column
        }
        val today = LocalDate.now()
        val airMs = e.airMs
        val onNow = airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L
        val plain = e.key.startsWith("follow|")
        if (!plain) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onNow) LiveTag("ON NOW", LIVE_RED, Color.White)
            e.tags.take(2).forEach { TagChip(it) }
            LiveTag(soonText(e, now, today).uppercase(), Color.White.copy(alpha = 0.16f), Color.White)
            if (e.watched) LiveTag("✓ WATCHED", Color.White.copy(alpha = 0.16f), Color.White)
        }
        LiveText(e.title.uppercase(), size = 28.sp, weight = FontWeight.ExtraBold, shadow = true, modifier = Modifier.width(560.dp))
        val line = if (plain) e.note.orEmpty() else when (e.kind) {
            CalendarKind.MOVIE -> e.note ?: if (CalendarTag.AT_HOME in e.tags) "Out to watch at home" else ""
            else -> listOfNotNull(
                e.episodeCode,
                seasonDrop(e),
                if (e.spoiler) "title hidden: you're not there yet" else e.episodeTitle
            ).joinToString(" · ")
        }
        if (line.isNotBlank()) LiveText(line, size = 16.sp, weight = FontWeight.SemiBold, shadow = true, modifier = Modifier.width(560.dp))
        val whenText = dayWord(e.day, today) + when {
            airMs != null && !e.streaming -> " " + formatClock(airMs, use24h)
            e.streaming && e.kind == CalendarKind.EPISODE -> " · streaming"
            else -> ""
        }
        if (!plain) LiveText(
            listOfNotNull(whenText, e.network, e.runtimeMin?.let { "$it min" }).joinToString(" · "),
            size = 13.sp, color = NuvioTheme.colors.TextSecondary, shadow = true
        )
        val about = e.description?.takeIf { !e.spoiler && it.isNotBlank() }
        if (about != null) LiveText(about, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, modifier = Modifier.width(560.dp))
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(4.dp))
    }
}

// -------------------------------------------------------------------- rows of cards, by day

private data class DayRow(val day: LocalDate, val list: List<CalendarEntry>)

@Composable
private fun DayRows(
    entries: List<CalendarEntry>,
    newestFirst: Boolean,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    restore: String? = null,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val rowStates = remember { HashMap<String, LazyListState>() }
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        rowStates.values.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val rows = remember(entries, newestFirst) {
        entries.distinctBy { it.key }.groupBy { it.day }.toList()
            .let { if (newestFirst) it.sortedByDescending { d -> d.first } else it.sortedBy { d -> d.first } }
            .map { (day, list) -> DayRow(day, list) }
    }
    val restoreAt = remember {
        restore?.let { k ->
            rows.withIndex().firstNotNullOfOrNull { (ri, r) ->
                r.list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { ci -> Triple(ri, r.day.toString(), ci) }
            }
        }
    }
    LaunchedEffect(Unit) {
        val (ri, _, _) = restoreAt ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == ri }) runCatching { listState.scrollToItem(ri) }
    }
    var held by remember { mutableStateOf(false) }
    var lastStep by remember { mutableStateOf(0L) }
    var resting by remember { mutableStateOf<CalendarEntry?>(null) }
    LaunchedEffect(resting, held) {
        val e = resting ?: return@LaunchedEffect
        if (!held) onFocus(e)
    }
    LazyColumn(
        state = listState, contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.onPreviewKeyEvent { k ->
            val code = k.nativeKeyEvent.keyCode
            if (k.type == KeyEventType.KeyDown && k.nativeKeyEvent.repeatCount == 0) held = false
            if (code != android.view.KeyEvent.KEYCODE_DPAD_DOWN && code != android.view.KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
            when (k.type) {
                KeyEventType.KeyUp -> { held = false; false }
                KeyEventType.KeyDown -> {
                    val t = android.os.SystemClock.uptimeMillis()
                    if (k.nativeKeyEvent.repeatCount > 0) {
                        held = true
                        if (t - lastStep < HELD_ROW_MS) return@onPreviewKeyEvent true
                    }
                    lastStep = t
                    false
                }
                else -> false
            }
        }
    ) {
        itemsIndexed(rows, key = { _, r -> r.day.toString() }) { rowIndex, r ->
            Column {
                Row(Modifier.padding(start = 64.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    LiveText(dayWord(r.day, today), size = 17.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    LiveText(r.day.format(DateTimeFormatter.ofPattern("EEE MMM d")), size = 13.sp, color = NuvioTheme.colors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    LiveText("· ${r.list.size}", size = 13.sp, color = NuvioTheme.colors.TextSecondary)
                }
                val rowKey = r.day.toString()
                LazyRow(
                    modifier = Modifier.keepFocusIn(right = true),
                    state = rowStates.getOrPut(rowKey) {
                        LazyListState(restoreAt?.takeIf { it.second == rowKey }?.let { (it.third - 1).coerceAtLeast(0) } ?: 0)
                    },
                    contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(r.list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now,
                            modifier = (if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                            onFocus = { resting = e }, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
    width: Dp = CARD_W,
    aspect: Float = 16f / 9f,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val airMs = e.airMs
    val onNow = airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L
    val height = width / aspect
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .size(width, height)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
            if (art != null) {
                AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            Row(Modifier.align(Alignment.TopStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                when {
                    onNow -> LiveTag("LIVE", LIVE_RED, Color.White)
                    e.watched -> LiveTag("✓", Color.Black.copy(alpha = 0.78f), Color.White)
                    e.tags.isNotEmpty() -> TagChip(e.tags.first())
                    else -> Unit
                }
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
                LiveText(
                    e.title.uppercase(),
                    size = 13.sp, weight = FontWeight.ExtraBold, color = Color.White, shadow = true,
                    modifier = Modifier.weight(1f), marquee = focused
                )
                e.network?.let {
                    Spacer(Modifier.width(6.dp))
                    LiveTag(it.take(14), Color.White.copy(alpha = 0.9f), Color(0xFF111111))
                }
            }
        }
        LiveText(
            when (e.kind) {
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, seasonDrop(e), if (e.spoiler) null else e.episodeTitle).joinToString(" · ")
            },
            size = 12.sp, weight = FontWeight.Medium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = 2.dp), marquee = focused
        )
    }
}

private fun seasonDrop(e: CalendarEntry): String? {
    val first = e.episode ?: return null
    val last = e.lastEpisode?.takeIf { it > first } ?: return null
    return "${last - first + 1} episodes" + if (first == 1) " (whole season)" else ""
}

// -------------------------------------------------------------------- the week

@Composable
private fun WeekView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    restore: String? = null,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val thisWeek = today.minusDays((today.dayOfWeek.value % 7).toLong())
    fun weekOf(day: LocalDate) = day.minusDays((day.dayOfWeek.value % 7).toLong())
    fun sortedDay(list: List<CalendarEntry>) = list.sortedBy { it.airMs ?: Long.MAX_VALUE }
    val restored = remember { restore?.let { k -> entries.firstOrNull { it.key == k } } }
    var start by rememberSaveable { mutableStateOf(restored?.let { weekOf(it.day) } ?: thisWeek) }
    val columnStates = remember {
        List(7) { d ->
            val at = restored?.takeIf { weekOf(it.day) == start && (it.day.dayOfWeek.value % 7) == d }
                ?.let { r -> sortedDay(entries.distinctBy { it.key }.filter { it.day == r.day }).indexOfFirst { it.key == r.key } }
            LazyListState((at ?: 0).coerceAtLeast(0))
        }
    }
    val prevWeekFocus = remember { FocusRequester() }
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        start = thisWeek
        columnStates.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    var focusedEntry by remember { mutableStateOf(restored) }
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(
                start.format(DateTimeFormatter.ofPattern("MMM d")) + " – " + start.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d")),
                size = 20.sp, weight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(10.dp))
            LiveText(
                when (start) { thisWeek -> "This week"; thisWeek.plusWeeks(1) -> "Next week"; thisWeek.minusWeeks(1) -> "Last week"; else -> "" },
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹ Week", false, Modifier.focusRequester(prevWeekFocus)) { start = start.minusWeeks(1) }
            Spacer(Modifier.width(6.dp))
            if (start != thisWeek) {
                Pill("This week", false) { runCatching { prevWeekFocus.requestFocus() }; start = thisWeek }
                Spacer(Modifier.width(6.dp))
            }
            Pill("Week ›", false) { start = start.plusWeeks(1) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in 0 until 7) {
                val day = start.plusDays(d.toLong())
                val list = sortedDay(byDay[day].orEmpty())
                val isToday = day == today
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isToday) NuvioTheme.colors.Secondary.copy(alpha = 0.12f) else Color.Transparent)
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        LiveText(
                            day.format(DateTimeFormatter.ofPattern("EEE")).uppercase(), size = 12.sp, weight = FontWeight.Bold,
                            color = if (isToday) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                        )
                        Spacer(Modifier.width(5.dp))
                        LiveText(
                            day.dayOfMonth.toString(), size = 16.sp, weight = FontWeight.ExtraBold,
                            color = if (day.isBefore(today)) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                        )
                    }
                    if (list.isEmpty()) {
                        LiveText("—", size = 13.sp, color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier.keepFocusIn(down = true),
                            state = columnStates[d],
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                                MiniCard(
                                    e = e, use24h = use24h, now = now,
                                    modifier = (if (i == 0 && (isToday || (start != thisWeek && d == 0))) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                                    onFocus = { focusedEntry = e },
                                    onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailBar(focusedEntry, use24h, now, Modifier.padding(bottom = 10.dp))
    }
}

@Composable
private fun DetailBar(e: CalendarEntry?, use24h: Boolean, now: Long, modifier: Modifier = Modifier) {
    val theme = NuvioTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(theme.BackgroundElevated).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (e == null) {
            LiveText("Highlight something to see its details here", size = 14.sp, color = theme.TextSecondary)
            return@Row
        }
        val airMs = e.airMs
        val onNow = airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(theme.BackgroundCard)) {
            val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(e.title, size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (onNow) LiveTag("LIVE", LIVE_RED, Color.White)
                else e.tags.firstOrNull()?.let { TagChip(it) }
            }
            LiveText(
                listOfNotNull(entryLine(e, use24h), e.runtimeMin?.let { "$it min" }).joinToString(" · "),
                size = 13.sp, color = theme.TextSecondary
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText("OK  Show page", size = 12.sp, color = theme.TextSecondary)
            LiveText("Hold OK  More options", size = 12.sp, color = theme.TextSecondary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    modifier: Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
            }
            val airMs = e.airMs
            if (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { LiveTag("LIVE", LIVE_RED, Color.White) }
            }
        }
        LiveText(e.title, size = 13.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, marquee = focused)
        LiveText(
            when (e.kind) {
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, e.network).joinToString(" · ")
            },
            size = 11.sp, color = NuvioTheme.colors.TextSecondary
        )
    }
}

// -------------------------------------------------------------------- the month

@Composable
private fun MonthView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    restore: String? = null,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val restoredDay = remember {
        restore?.let { k ->
            entries.firstOrNull { it.key == k }?.day
                ?: k.takeIf { it.startsWith("day|") }?.let { runCatching { LocalDate.parse(it.removePrefix("day|")) }.getOrNull() }
        }
    }
    var shown by rememberSaveable { mutableStateOf(restoredDay?.let { YearMonth.from(it) } ?: YearMonth.now()) }
    var selected by rememberSaveable { mutableStateOf(restoredDay ?: today) }
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        shown = YearMonth.now()
        selected = today
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    val stripFocus = remember { FocusRequester() }
    val list = byDay[selected].orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize().padding(end = 32.dp)) {
        val stripGap = 10.dp
        val stripW = (maxWidth - 64.dp - 6.dp - stripGap * 4) / 5
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), size = 22.sp, weight = FontWeight.ExtraBold)
                Spacer(Modifier.width(10.dp))
                LiveText(
                    (if (list.isEmpty()) "Nothing out" else "${list.size} on this day") + " · " + dayWord(selected, today).lowercase(),
                    size = 13.sp, color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.weight(1f))
                Pill("‹", false) { shown = shown.minusMonths(1) }
                LiveText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                Pill("›", false) { shown = shown.plusMonths(1) }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(stripW / 2f + 34.dp)) {
                if (list.isEmpty()) {
                    LiveText("Highlight a day with a picture to see what's out", size = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 64.dp, top = 30.dp))
                } else {
                    val stripState = remember(selected) {
                        LazyListState(restore?.let { k -> list.indexOfFirst { it.key == k } }?.takeIf { it > 0 }?.minus(1) ?: 0)
                    }
                    LazyRow(
                        modifier = Modifier.keepFocusIn(right = true),
                        state = stripState,
                        contentPadding = PaddingValues(start = 64.dp, end = 6.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(stripGap)
                    ) {
                        itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                            WideCard(
                                e = e, use24h = use24h, now = now, width = stripW, aspect = 2f,
                                modifier = (if (i == 0) Modifier.focusRequester(stripFocus) else Modifier).cardFocus(e.key),
                                onFocus = {}, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(start = 64.dp)) {
                listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                    LiveText(it, size = 10.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            val firstDay = shown.atDay(1)
            val start = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong())
            val weeks = ((ChronoUnit.DAYS.between(start, shown.atEndOfMonth()) / 7) + 1).toInt()
            Column(Modifier.weight(1f).padding(start = 64.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (w in 0 until weeks) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 0 until 7) {
                            val day = start.plusDays((w * 7 + d).toLong())
                            DayCell(
                                day = day,
                                inMonth = day.month == shown.month,
                                today = day == today,
                                entries = byDay[day].orEmpty(),
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .then(if (day == today && shown == YearMonth.now()) Modifier.focusRequester(firstFocus).homeMark() else Modifier)
                                    .cardFocus(dayKey(day)),
                                onFocus = { selected = day },
                                onClick = { if (byDay[day].orEmpty().isNotEmpty()) runCatching { stripFocus.requestFocus() } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    entries: List<CalendarEntry>,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val top = entries.firstOrNull()
    val art = when {
        top == null -> null
        top.spoiler -> top.background ?: top.poster
        else -> top.episodeImage ?: top.background ?: top.poster
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (inMonth) 1f else 0.45f }
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundCard)
            .border(if (today) 2.dp else 0.dp, if (today) NuvioTheme.colors.Secondary else Color.Transparent, shape)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        }
        LiveText(
            if (day.dayOfMonth == 1) day.format(DateTimeFormatter.ofPattern("MMM d")) else day.dayOfMonth.toString(),
            size = 12.sp, weight = if (today || focused) FontWeight.ExtraBold else FontWeight.SemiBold, color = Color.White, shadow = true,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
        )
        if (top != null) {
            CellCaption(top.title, entries.size)
        }
    }
}

@Composable
private fun BoxScope.CellCaption(title: String, count: Int) {
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(title, size = 10.sp, weight = FontWeight.Bold, color = Color.White, shadow = true, modifier = Modifier.weight(1f))
        if (count > 1) LiveTag("+${count - 1}", Color.Black.copy(alpha = 0.78f), Color.White)
    }
}

// -------------------------------------------------------------------- posters (Airing Today, Following)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    poster: String?,
    title: String,
    caption: String,
    corner: String?,
    bell: Boolean,
    tag: CalendarTag?,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else LiveText(title, size = 13.sp, weight = FontWeight.Bold, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                corner?.let { LiveTag(it, Color.Black.copy(alpha = 0.78f), Color.White) }
                Spacer(Modifier.weight(1f))
                if (bell) Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.Secondary), contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Icon(Icons.Default.Notifications, contentDescription = "Bell on", tint = NuvioTheme.colors.OnSecondary, modifier = Modifier.size(12.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                tag?.let { TagChip(it) }
            }
        }
        LiveText(title, size = 12.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, marquee = focused)
        LiveText(caption, size = 11.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun PosterGrid(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    isFollowed: (CalendarEntry) -> Boolean,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val list = remember(entries) { entries.distinctBy { it.key } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
            PosterCard(
                poster = e.poster,
                title = e.title,
                caption = listOfNotNull(e.episodeCode, e.network).joinToString(" · "),
                corner = cardTime(e, use24h),
                bell = e.followed || isFollowed(e),
                tag = e.tags.firstOrNull { it != CalendarTag.NEW },
                modifier = (if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                onFocus = { onFocus(e) },
                onClick = { onOpen(e) },
                onLongClick = { onOptions(e) }
            )
        }
    }
}

@Composable
private fun FollowingGrid(
    follows: List<CalendarFollow>,
    entries: List<CalendarEntry>,
    now: Long,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    onFocus: (CalendarFollow) -> Unit,
    onOpen: (CalendarFollow) -> Unit,
    onOptions: (CalendarFollow) -> Unit
) {
    val today = LocalDate.now()
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(follows, key = { _, f -> f.id }) { i, f ->
            val next = entries.firstOrNull { (it.contentId == (f.imdbId ?: f.id) || it.contentId == f.id || it.key == f.id) && whenOf(it) > now }
            val caption = when {
                next != null -> "Next: ${dayWord(next.day, today)}" + (next.episodeCode?.let { " · $it" } ?: "")
                f.type == "movie" -> "Waiting to come out"
                else -> "Nothing announced yet"
            }
            PosterCard(
                poster = f.poster, title = f.name, caption = caption, corner = null, bell = true, tag = null,
                modifier = (if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus("follow|" + f.id),
                onFocus = { onFocus(f) }, onClick = { onOpen(f) }, onLongClick = { onOptions(f) }
            )
        }
    }
}

// -------------------------------------------------------------------- small parts

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, quiet: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val theme = NuvioTheme.colors
    val colors = liveCellColors(focused, if (selected) theme.Secondary else theme.BackgroundElevated)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) (if (quiet) theme.Secondary.copy(alpha = 0.26f) else theme.Secondary) else colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (quiet) 11.dp else 13.dp, vertical = if (quiet) 4.dp else 6.dp)
    ) {
        LiveText(
            text, size = 12.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected && !quiet) theme.OnSecondary else if (selected) theme.TextPrimary else colors.text
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val theme = NuvioTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (focused) theme.FocusBackground else Color.Transparent)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            LiveText(
                text, size = 15.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected || focused) theme.TextPrimary else theme.TextSecondary
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) theme.Secondary else Color.Transparent))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitchPill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.Transparent)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwitchMark(on)
        Spacer(Modifier.width(7.dp))
        LiveText(text, size = 12.sp, weight = FontWeight.Medium, color = colors.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    selectedModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(NuvioTheme.colors.BackgroundElevated).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (id, label) ->
            var focused by remember { mutableStateOf(false) }
            val on = id == selected
            val theme = NuvioTheme.colors
            val colors = liveCellColors(focused, if (on) theme.Secondary else Color.Transparent)
            Box(
                (if (on) selectedModifier else Modifier)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) theme.Secondary else colors.background)
                    .border(if (focused) 2.dp else 0.dp, colors.border, RoundedCornerShape(50))
                    .onFocusChanged { focused = it.isFocused }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onPick(id) })
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                LiveText(
                    label, size = 12.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) theme.OnSecondary else colors.text
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 18.dp).background(NuvioTheme.colors.Border))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, NuvioTheme.colors.BackgroundElevated)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .size(30.dp)
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TagChip(tag: CalendarTag) {
    val (bg, fg) = when (tag) {
        CalendarTag.NEW -> LIVE_RED to Color.White
        CalendarTag.SERIES_PREMIERE, CalendarTag.SEASON_PREMIERE -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
        CalendarTag.SEASON_FINALE, CalendarTag.SERIES_FINALE -> Color(0xFF8A4B12) to Color(0xFFFFE2C7)
        CalendarTag.AT_HOME -> Color(0xFF1C6B45) to Color(0xFFD6F5E4)
        CalendarTag.IN_THEATERS, CalendarTag.ON_DISC -> Color(0xFF3A3D48) to Color(0xFFE6E8EE)
        CalendarTag.ON_TV -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
    }
    LiveTag(tag.label.uppercase(), bg, fg)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyNote(text: String, headline: String? = null, action: String? = null, actionFocus: FocusRequester? = null, onAction: () -> Unit = {}) {
    val theme = NuvioTheme.colors
    Box(Modifier.fillMaxSize().padding(top = 24.dp, start = 64.dp, end = 40.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(theme.BackgroundElevated).padding(horizontal = 20.dp, vertical = 16.dp).width(620.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (headline != null) LiveText(headline, size = 18.sp, weight = FontWeight.SemiBold)
            LiveText(text, size = 15.sp, color = theme.TextSecondary, maxLines = 4)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .then(if (actionFocus != null) Modifier.focusRequester(actionFocus) else Modifier)
                        .clip(shape)
                        .background(theme.Secondary)
                        .focusRing(focused, shape)
                        .onFocusChanged { focused = it.isFocused }
                        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAction)
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) { LiveText(action, size = 13.sp, weight = FontWeight.SemiBold, color = theme.OnSecondary) }
            }
        }
    }
}

private fun whenOf(e: CalendarEntry): Long =
    e.airMs ?: e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun cardTime(e: CalendarEntry, use24h: Boolean): String {
    val airMs = e.airMs
    return when {
        e.kind == CalendarKind.MOVIE && CalendarTag.ON_TV in e.tags && airMs != null -> formatClock(airMs, use24h)
        e.kind == CalendarKind.MOVIE -> if (CalendarTag.IN_THEATERS in e.tags) "In theaters" else if (CalendarTag.ON_DISC in e.tags) "On disc" else "At home"
        airMs != null && !e.streaming -> formatClock(airMs, use24h)
        e.streaming -> "Streaming"
        else -> "All day"
    }
}

private fun soonText(e: CalendarEntry, now: Long, today: LocalDate): String {
    val at = whenOf(e)
    val days = ChronoUnit.DAYS.between(today, e.day)
    return when {
        at <= now && days == 0L -> "Today"
        at <= now -> if (days == -1L) "Yesterday" else "${-days} days ago"
        days == 0L && e.airMs != null -> ((at - now) / 3_600_000L).let { h -> if (h < 1) "In ${((at - now) / 60_000L).coerceAtLeast(1)} min" else "In $h h" }
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        else -> "In $days days"
    }
}

private fun dayWord(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEEE"))
    else -> day.format(DateTimeFormatter.ofPattern("EEE MMM d"))
}
