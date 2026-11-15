package com.nuvio.tv.youtube

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.DebugReportDialog
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import com.nuvio.tv.core.qr.QrCodeGenerator

private val YtRed = Color(0xFFFF0033)
private val CardBg = Color(0xFF1C1C1E)

/**
 * The YouTube page: its own section in the side menu, laid out like the YouTube app: feeds along
 * the top, then rows or a grid. Videos play in Nuvio's own player via [onPlay]; nothing here feeds
 * Home or Continue Watching.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouTubeScreen(
    onPlay: (YtPlayable) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: YouTubeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val cardMenu by viewModel.menu.collectAsState()

    // YouTube changes its recommendations over time. A TV app can stay in memory for days, so
    // reload a stale feed when the app comes back instead of showing the same rows forever.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Coming back to OneTube from another tab should refresh it too, if it has gone stale.
    LaunchedEffect(Unit) { viewModel.refreshIfStale() }

    var query by remember { mutableStateOf("") }
    // OneTube sits slightly smaller than the rest of the app, so a 1080p TV shows more cards
    // and the header/tabs stop feeling oversized. Everything inside the page scales together.
    val baseDensity = LocalDensity.current
    val contentDensity = remember(baseDensity) {
        Density(baseDensity.density * 0.90f, baseDensity.fontScale)
    }

    // Signing in happens on this page: the code shows here and the tab reloads when it's approved.
    val accountViewModel: YouTubeAccountViewModel = hiltViewModel()
    val account by accountViewModel.state.collectAsState()
    var signInStarted by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var suggestionsOpen by remember { mutableStateOf(false) }
    val suggestions by viewModel.suggestions.collectAsState()
    val firstSuggestion = remember { FocusRequester() }
    // Search stays a magnifier until it's opened, so the header isn't dominated by a text box.
    var searchExpanded by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var collapseToSearchIcon by remember { mutableStateOf(false) }
    // Scrolling down through the cards collapses the header and tab strip so more cards fit.
    var headerHidden by remember { mutableStateOf(false) }
    var headerHideSuppressedUntil by remember { mutableLongStateOf(0L) }
    var focusHeaderAfterBack by remember { mutableStateOf(false) }
    var showDebugReport by remember { mutableStateOf(false) }
    val selectedTabFocus = remember { FocusRequester() }
    val searchIconFocus = remember { FocusRequester() }
    // Down from any tab should land on the first video card, not whichever card happens
    // to sit underneath that tab.
    val firstCardFocus = remember { FocusRequester() }
    var feedScrollToTopTick by remember { mutableIntStateOf(0) }
    val focusFeedFromTabs = {
        if (state.rows.isNotEmpty() || state.grid.isNotEmpty()) feedScrollToTopTick++
    }
    val tabsDownFocus = if (state.rows.isNotEmpty() || state.grid.isNotEmpty()) firstCardFocus else null
    // Down from the header should land on the tab you're actually on, not whichever tab
    // happens to sit underneath the search icon.
    val headerDownFocus = if (state.subTitle == null) selectedTabFocus else null
    // Kept outside the collapsing band so the tab strip doesn't jump back to the start when the
    // header is shown again.
    val tabScrollState = rememberScrollState()
    // Keep focused tabs where they are when they're already fully visible. The default
    // bring-into-view spec centres them, which used to scroll Home off-screen just for pressing
    // Up on the first card.
    val tabDensity = contentDensity
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val tabBringIntoViewSpec = remember(tabDensity, defaultBringIntoViewSpec) {
        val margin = with(tabDensity) { 8.dp.toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = defaultBringIntoViewSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                val end = offset + abs(size)
                return when {
                    offset < margin -> offset - margin
                    end > containerSize - margin -> end - (containerSize - margin)
                    else -> 0f
                }
            }
        }
    }

    // The tabs you've left on in Settings. Read each time you come to the page.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // The centred logo needs room either side of it; on a narrower TV the account chip steps
    // aside while the search box is open instead of colliding with the title.
    val compactHeader = appContext.resources.configuration.screenWidthDp < 1100
    val tabs = remember { YouTubeSettings.visibleTabs(appContext) }
    var pinnedPlaylists by remember { mutableStateOf(YouTubeSettings.pinnedPlaylists(appContext)) }
    // Pinning or unpinning happens in the card menu; refresh the row when that closes.
    LaunchedEffect(cardMenu) {
        if (cardMenu == null) pinnedPlaylists = YouTubeSettings.pinnedPlaylists(appContext)
    }
    LaunchedEffect(tabs) {
        // A tab you've since turned off can't stay open.
        if (state.tab !in tabs) viewModel.selectTab(tabs.first())
    }
    LaunchedEffect(Unit) { accountViewModel.refresh() }
    LaunchedEffect(account.signedIn) {
        if (signInStarted && account.signedIn) {
            signInStarted = false
            viewModel.selectTab(state.tab)
        }
    }
    // The page can open before the account is known; once it turns out you're signed in, load your feed.
    LaunchedEffect(account.checked, account.signedIn) {
        if (account.checked && account.signedIn && !state.signedIn) viewModel.selectTab(state.tab)
    }
    // A sign-in that YouTube's account list didn't answer keeps the token but not the name yet.
    var nameRefreshTried by remember { mutableStateOf(false) }
    LaunchedEffect(account.checked, account.signedIn, account.accountName) {
        if (account.checked && account.signedIn && account.accountName == null && !nameRefreshTried) {
            nameRefreshTried = true
            accountViewModel.refreshAccountInfo()
        }
    }
    // An error from a card action is useful for a moment, then it should get out of the way.
    LaunchedEffect(error) {
        if (error != null) {
            delay(6000)
            viewModel.clearError()
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val openNuvioSidebar = com.nuvio.tv.LocalOpenSidebar.current

    // Leaving the search behind (another tab, a card, settings) should put the magnifier back,
    // not leave a text box sitting in the header of the next page.
    fun collapseSearch() {
        keyboard?.hide()
        suggestionsOpen = false
        searchEditing = false
        searchExpanded = false
        query = ""
    }

    // After the search box closes, the remote's highlight goes back to the magnifier.
    LaunchedEffect(collapseToSearchIcon) {
        if (collapseToSearchIcon) {
            runCatching { searchIconFocus.requestFocus() }
            collapseToSearchIcon = false
        }
    }

    // Back goes up from a channel, playlist or search before it leaves the page.
    BackHandler(enabled = state.subTitle == null && !showAccounts && !suggestionsOpen && !sidebarExpanded) { openNuvioSidebar() }
    BackHandler(enabled = state.subTitle != null) {
        viewModel.back()
        // Put focus back on the tab strip so Down can enter the restored feed. Without this,
        // focus stayed on the removed channel/playlist page and the remote seemed stuck.
        headerHidden = false
        focusHeaderAfterBack = true
    }
    BackHandler(enabled = showAccounts) { showAccounts = false }
    BackHandler(enabled = suggestionsOpen) { suggestionsOpen = false }
    BackHandler(
        enabled = headerHidden && state.subTitle == null && !showAccounts &&
            !suggestionsOpen && !sidebarExpanded
    ) {
        headerHidden = false
        headerHideSuppressedUntil = android.os.SystemClock.elapsedRealtime() + 700L
        focusHeaderAfterBack = true
    }

    LaunchedEffect(focusHeaderAfterBack, headerHidden) {
        if (focusHeaderAfterBack && !headerHidden) {
            // The header expands with an animation, so the selected tab may not be placed on
            // the first frame. Keep asking across a few frames until focus actually lands.
            repeat(8) {
                delay(30)
                if (runCatching { selectedTabFocus.requestFocus() }.isSuccess) {
                    focusHeaderAfterBack = false
                    return@LaunchedEffect
                }
            }
            focusHeaderAfterBack = false
        }
    }

    CompositionLocalProvider(LocalDensity provides contentDensity) {
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 16.dp)) {
        // The header and tab strip share one collapsing band, so the cards get the full screen
        // height while browsing and the whole header comes back on one press.
        AnimatedVisibility(
            visible = !headerHidden,
            enter = expandVertically(tween(220)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(120))
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    // Clock on the far left, when it's on in Settings.
                    if (YouTubeSettings.showClock(appContext)) {
                        HeaderClock(Modifier.align(Alignment.CenterStart))
                    }
                    // The OneTube tile and page name sit dead centre, whatever is either side.
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .widthIn(max = if (searchExpanded) 220.dp else 320.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.onetube_mark_red),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            state.subTitle ?: "OneTube",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Search, settings and the account, together at the end.
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!searchExpanded) {
                            IconChip(
                                Icons.Default.Search,
                                "Search",
                                searchIconFocus,
                                downFocusRequester = headerDownFocus
                            ) {
                                searchExpanded = true
                                // Open straight into typing: the field takes focus and the
                                // keyboard comes up without an extra OK press.
                                searchEditing = true
                            }
                        } else {
                            // The box is selected like any other item on the page (it's just something the arrows land on),
                            // and only starts taking text, with the keyboard, once OK is pressed on it.
                            var boxFocused by remember { mutableStateOf(false) }
                            var boxHadFocus by remember { mutableStateOf(false) }
                            var textPlaced by remember { mutableStateOf(false) }
                            // The field gets a focus-lost callback as it enters composition. Only
                            // leave typing mode after it has actually held focus and then lost it.
                            var hadTextFocus by remember { mutableStateOf(false) }
                            val textFocus = remember { FocusRequester() }
                            val clearFocus = remember { FocusRequester() }
                            LaunchedEffect(searchEditing, textPlaced) {
                                if (searchEditing && textPlaced) {
                                    // Focus restoration after the search icon disappears can land on the
                                    // account chip. Keep asking for the field across a few frames until
                                    // it actually owns focus.
                                    repeat(6) {
                                        delay(30)
                                        if (runCatching { textFocus.requestFocus() }.isSuccess) {
                                            keyboard?.show()
                                            return@LaunchedEffect
                                        }
                                    }
                                    keyboard?.show()
                                }
                            }
                            // Back out of the search: the keyboard goes, the suggestions close, and the
                            // magnifier takes its place again. The query is kept for the next time it opens.
                            BackHandler {
                                keyboard?.hide()
                                suggestionsOpen = false
                                searchEditing = false
                                searchExpanded = false
                                query = ""
                                collapseToSearchIcon = true
                            }
                            Box(
                                Modifier
                                    .width(280.dp)
                                    .focusRequester(searchIconFocus)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(CardBg)
                                    .border(2.dp, if (boxFocused || searchEditing) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
                                    // Down from the box goes into the suggestions when they're showing.
                                    .focusProperties {
                                        if (query.isNotEmpty()) right = clearFocus
                                        if (suggestionsOpen && suggestions.isNotEmpty()) down = firstSuggestion
                                        else if (headerDownFocus != null) down = headerDownFocus
                                    }
                                    .onFocusChanged { focusState ->
                                        boxFocused = focusState.isFocused
                                        // Moving focus out to the page (not down into a visible
                                        // suggestion list) means the search is done: collapse it.
                                        if (boxHadFocus && !focusState.hasFocus &&
                                            !(suggestionsOpen && suggestions.isNotEmpty())
                                        ) {
                                            searchEditing = false
                                            searchExpanded = false
                                        }
                                        boxHadFocus = focusState.hasFocus
                                    }
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) { searchEditing = true }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    BasicTextField(
                                        value = query,
                                        onValueChange = {
                                            query = it
                                            viewModel.suggest(it)
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                                        cursorBrush = SolidColor(Color.White),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            // Close the keyboard and move on to the results.
                                            keyboard?.hide()
                                            suggestionsOpen = false
                                            searchEditing = false
                                            focusManager.moveFocus(FocusDirection.Down)
                                            viewModel.search(query)
                                            query = ""
                                        }),
                                        modifier = Modifier
                                            .weight(1f)
                                            .onPlaced { textPlaced = true }
                                            .focusRequester(textFocus)
                                            // Only while typing: the arrows never land inside the text field by themselves.
                                            .focusProperties {
                                                canFocus = searchEditing
                                                if (suggestionsOpen && suggestions.isNotEmpty()) {
                                                    down = firstSuggestion
                                                } else if (headerDownFocus != null) {
                                                    down = headerDownFocus
                                                }
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    hadTextFocus = true
                                                    suggestionsOpen = true
                                                    viewModel.suggest(query)
                                                } else if (hadTextFocus && searchEditing) {
                                                    // Focus left the box (down into the suggestions, say): done typing.
                                                    hadTextFocus = false
                                                    searchEditing = false
                                                    // With no suggestion list to browse, the arrows have moved on to the
                                                    // page itself, so put the magnifier back instead of leaving the box open.
                                                    if (!(suggestionsOpen && suggestions.isNotEmpty())) {
                                                        searchExpanded = false
                                                    }
                                                }
                                            },
                                        decorationBox = { inner ->
                                            if (query.isEmpty()) Text("Search OneTube", color = Color.Gray, fontSize = 18.sp)
                                            inner()
                                        }
                                    )
                                    if (query.isNotEmpty()) {
                                        var clearFocused by remember { mutableStateOf(false) }
                                        Box(
                                            Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (clearFocused) Color.White.copy(alpha = 0.16f)
                                                    else Color.Transparent
                                                )
                                                .focusRequester(clearFocus)
                                                .onFocusChanged { clearFocused = it.isFocused }
                                                .clickable {
                                                    query = ""
                                                    viewModel.suggest("")
                                                    if (searchEditing) {
                                                        runCatching { textFocus.requestFocus() }
                                                    } else {
                                                        runCatching { searchIconFocus.requestFocus() }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Clear search",
                                                tint = if (clearFocused) Color.White else Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        IconChip(
                            Icons.Default.Settings,
                            "OneTube settings",
                            downFocusRequester = headerDownFocus
                        ) {
                            collapseSearch()
                            onOpenSettings()
                        }
                        IconChip(
                            Icons.Default.BugReport,
                            "Send debug report",
                            downFocusRequester = headerDownFocus
                        ) {
                            collapseSearch()
                            showDebugReport = true
                        }
                        // While the search box is open it takes the room the account chip would
                        // use, so the centred title never collides with the controls.
                        if (!searchExpanded) {
                            AccountChip(
                                label = when {
                                    account.signedIn -> account.accountName ?: "Account"
                                    !account.checked -> "Account"
                                    else -> "Sign in"
                                },
                                signedIn = account.signedIn,
                                downFocusRequester = headerDownFocus
                            ) {
                                if (account.signedIn) showAccounts = !showAccounts
                                else {
                                    signInStarted = true
                                    accountViewModel.startSignIn()
                                }
                            }
                        }
                    }
                }

                // The tabs stay centred under the header. If the enabled tabs are wider than the
                // screen the row still scrolls normally instead of clipping the first tab.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val tabsMaxWidth = maxWidth
                    CompositionLocalProvider(LocalBringIntoViewSpec provides tabBringIntoViewSpec) {
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .horizontalScroll(tabScrollState)
                                .widthIn(min = tabsMaxWidth)
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .padding(top = 8.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEach { tab ->
                                TabChip(
                                    tab.title,
                                    selected = state.tab == tab && state.subTitle == null,
                                    focusRequester = if (state.tab == tab) selectedTabFocus else null,
                                    downFocusRequester = tabsDownFocus,
                                    onDownKey = focusFeedFromTabs
                                ) {
                                    query = ""
                                    collapseSearch()
                                    viewModel.selectTab(tab)
                                }
                            }
                        }
                        // With every tab switched on the strip can be wider than the TV; the
                        // chevrons say there are more tabs that way instead of leaving them hidden.
                        if (tabScrollState.value > 0) {
                            RowScrollCue(Alignment.CenterStart, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
                        }
                        if (tabScrollState.value < tabScrollState.maxValue) {
                            RowScrollCue(Alignment.CenterEnd, Icons.AutoMirrored.Filled.KeyboardArrowRight)
                        }
                    }
                }
            }
        }
        }

        // Playlists pinned from their card menu, so they're one press away from the top of the page.
        if (pinnedPlaylists.isNotEmpty() && state.subTitle == null) {
            LazyRow(
                Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Text("Pinned", color = Color.Gray, fontSize = 14.sp)
                }
                items(pinnedPlaylists) { (id, title) ->
                    TabChip(
                        title,
                        selected = false,
                        upFocusRequester = if (!headerHidden) selectedTabFocus else null,
                        downFocusRequester = tabsDownFocus,
                        onDownKey = focusFeedFromTabs
                    ) {
                        query = ""
                        collapseSearch()
                        viewModel.openPlaylistById(id, title)
                    }
                }
            }
        }

        error?.let {
            Text(it, color = YtRed, modifier = Modifier.padding(bottom = 8.dp).clickable { viewModel.clearError() })
        }

        // A failed sign-in used to be silent: the code screen closed and nothing said why.
        account.error?.let {
            Text(it, color = YtRed, modifier = Modifier.padding(bottom = 8.dp))
        }

        if (suggestionsOpen && suggestions.isNotEmpty()) {
            SuggestionList(
                modifier = Modifier.weight(1f, fill = false),
                items = suggestions,
                recent = query.isBlank(),
                first = firstSuggestion,
                onPick = { text ->
                    query = text
                    suggestionsOpen = false
                    keyboard?.hide()
                    viewModel.search(text)
                },
                onClearHistory = {
                    viewModel.clearSearchHistory()
                    suggestionsOpen = false
                }
            )
        } else Content(
            state = state,
            account = account,
            viewModel = viewModel,
            onPlay = { playable ->
                collapseSearch()
                onPlay(playable)
            },
            onSignIn = {
                signInStarted = true
                accountViewModel.startSignIn()
            },
            onCancelSignIn = {
                signInStarted = false
                accountViewModel.cancelSignIn()
            },
            showAccounts = showAccounts,
            onSelectAccount = { id ->
                showAccounts = false
                accountViewModel.selectAccount(id) { viewModel.selectTab(state.tab) }
            },
            onAddAccount = {
                showAccounts = false
                signInStarted = true
                accountViewModel.startSignIn()
            },
            onSignOut = {
                showAccounts = false
                accountViewModel.signOut { viewModel.selectTab(state.tab) }
            },
            // Up from the first row should land on the tab that's actually selected, not
            // whichever tab happens to sit under the card by geometry. A channel or playlist
            // has no selected tab, so there is nothing to point at then.
            tabsFocus = if (!headerHidden && state.subTitle == null) selectedTabFocus else null,
            firstCardFocus = firstCardFocus,
            feedScrollToTopTick = feedScrollToTopTick,
            onScrollPosition = { index, offset ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (index == 0 && offset <= 24) {
                    if (headerHidden && now >= headerHideSuppressedUntil) {
                        headerHidden = false
                        headerHideSuppressedUntil = now + 700L
                    }
                } else if (now >= headerHideSuppressedUntil) {
                    headerHidden = true
                }
            }
        )
    }
    }

    cardMenu?.let { open ->
        YouTubeItemMenuDialog(
            menu = open,
            onClose = { viewModel.closeMenu() },
            onOpenChannel = {
                open.item.channelId?.let { id ->
                    viewModel.openChannel(id, open.item.author?.takeIf { it.isNotBlank() } ?: open.item.title)
                }
                viewModel.closeMenu()
            },
            onOpenItem = {
                viewModel.open(open.item)
                viewModel.closeMenu()
            },
            onToggleSubscribe = { viewModel.menuToggleSubscribe() },
            onTogglePlaylist = { viewModel.menuTogglePlaylist(it) },
            onNewPlaylist = { viewModel.menuCreatePlaylist(it) },
            onTogglePinned = { viewModel.menuTogglePinned() },
            onDeletePlaylist = { viewModel.menuDeletePlaylist() },
            onNotInterested = { viewModel.menuNotInterested() },
            onBlockChannel = { viewModel.menuBlockChannel() }
        )
    }

    if (showDebugReport) {
        DebugReportDialog(onDismiss = { showDebugReport = false })
    }
}

@Composable
private fun Content(
    state: YtUiState,
    account: YouTubeAccountState,
    viewModel: YouTubeViewModel,
    onPlay: (YtPlayable) -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    showAccounts: Boolean,
    onSelectAccount: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit,
    tabsFocus: FocusRequester? = null,
    firstCardFocus: FocusRequester? = null,
    feedScrollToTopTick: Int = 0,
    onScrollPosition: (Int, Int) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Grids follow the "Posters per row" setting; rows are 16:9 and get their own width so
    // about four cards fit on a 1080p TV, with the next one peeking in at the edge.
    val columnCount = YouTubeSettings.posterColumns(context)
    val screenWidthDp = context.resources.configuration.screenWidthDp
    // The same "Posters per row" choice drives horizontal feed rows and grids. It used to
    // affect only grids, so changing it from 5 to 3 or 4 left the Home rows looking identical.
    val rowCardWidth = run {
        val gapDp = 16f * (columnCount - 1).coerceAtLeast(0)
        ((screenWidthDp - 56f - gapDp) / columnCount.toFloat())
            .coerceIn(150f, 340f)
            .dp
    }
    var needsInitialFocus by remember { mutableStateOf(true) }
    // The row whose cards last held focus. Re-running the vertical animate-scroll for every
    // horizontal focus move made the whole feed bounce while browsing a row.
    var lastFocusedRow by remember(state.tab, state.subTitle) { mutableStateOf(-1) }
    val canRequestInitialFocus = needsInitialFocus && !com.nuvio.tv.LocalSidebarExpanded.current
    val restoreId = viewModel.lastPlayedVideoId
    val firstRowWithVideo = state.rows.indexOfFirst { row -> row.items.any { it.kind == YtKind.VIDEO } }
    val restoredRow = state.rows.indexOfFirst { row -> row.items.any { it.kind == YtKind.VIDEO && it.videoId == restoreId } }
    val targetRow = if (restoredRow >= 0) restoredRow else firstRowWithVideo
    val targetRowVideo = state.rows.getOrNull(targetRow)?.items?.let { items ->
        items.indexOfFirst { it.kind == YtKind.VIDEO && it.videoId == restoreId }.takeIf { it >= 0 }
            ?: items.indexOfFirst { it.kind == YtKind.VIDEO }
    } ?: -1
    val restoredGridIndex = state.grid.indexOfFirst { it.kind == YtKind.VIDEO && it.videoId == restoreId }
    val targetGridIndex = if (restoredGridIndex >= 0) restoredGridIndex else state.grid.indexOfFirst { it.kind == YtKind.VIDEO }
    // Down from the tabs always goes to the first row on the page, even when that row is not
    // made of videos (Gaming's "Top Live Games", for example). Use its first video if it has
    // one, otherwise its first card.
    val firstRowIndex = state.rows.indexOfFirst { it.items.isNotEmpty() }.takeIf { it >= 0 } ?: 0
    val firstRowCardIndex = state.rows.getOrNull(firstRowIndex)?.items?.let { items ->
        items.indexOfFirst { it.kind == YtKind.VIDEO }.takeIf { it >= 0 } ?: 0
    } ?: -1
    val firstGridCardIndex = state.grid.indexOfFirst { it.kind == YtKind.VIDEO }.takeIf { it >= 0 }
        ?: if (state.grid.isNotEmpty()) 0 else -1
    when {
        account.code != null -> SignInPanel(account, onCancelSignIn)

        showAccounts -> AccountsPanel(account, onSelectAccount, onAddAccount, onSignOut)

        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = YtRed) }

        !account.checked && !state.signedIn && state.subTitle == null && (state.tab.needsSignIn || state.tab == YtTab.HOME) ->
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = YtRed) }

        state.tab.needsSignIn && !state.signedIn && state.subTitle == null -> Message(
            "Sign in to see your ${state.tab.title.lowercase()}",
            account.error ?: "You'll get a code to enter at ${account.url}.",
            "Sign in",
            upFocusRequester = tabsFocus,
            onAction = onSignIn
        )

        state.error != null && state.isEmpty -> Message(
            state.error,
            "Check your connection and try again.",
            "Try again",
            upFocusRequester = tabsFocus
        ) {
            // Take the remote back to the content once it loads, rather than leaving it on the
            // header after the button disappears.
            needsInitialFocus = true
            viewModel.selectTab(state.tab)
        }

        state.isEmpty && !state.signedIn && state.tab == YtTab.HOME && state.subTitle == null -> Message(
            "Home is picked for you",
            "OneTube only fills it once you sign in. Try Live, Music or Gaming above, or sign in.",
            "Sign in",
            upFocusRequester = tabsFocus,
            onAction = onSignIn
        )

        state.isEmpty -> Message(
            "Nothing here yet",
            "OneTube didn't send this page. It can be busy for a moment.",
            "Try again",
            upFocusRequester = tabsFocus
        ) {
            needsInitialFocus = true
            viewModel.selectTab(state.tab)
        }

        state.rows.isNotEmpty() -> {
          val rowsState = rememberLazyListState()
          val rowsScope = rememberCoroutineScope()
          LaunchedEffect(state.subTitle) {
              if (state.subTitle != null && state.rows.isNotEmpty()) rowsState.scrollToItem(0)
          }
          LaunchedEffect(needsInitialFocus, targetRow) {
              if (needsInitialFocus && targetRow >= 0) rowsState.scrollToItem(targetRow)
          }
          LaunchedEffect(feedScrollToTopTick) {
              if (feedScrollToTopTick > 0) {
                  rowsState.scrollToItem(0)
                  delay(80)
                  firstCardFocus?.let { runCatching { it.requestFocus() } }
              }
          }
          LaunchedEffect(rowsState) {
              snapshotFlow {
                  rowsState.firstVisibleItemIndex to rowsState.firstVisibleItemScrollOffset
              }.collect { (index, offset) -> onScrollPosition(index, offset) }
          }
          LazyColumn(
              Modifier.fillMaxSize(),
              state = rowsState,
              contentPadding = PaddingValues(bottom = 36.dp),
              verticalArrangement = Arrangement.spacedBy(26.dp)
          ) {
            itemsIndexed(state.rows) { rowIndex, row ->
                val rowState = rememberLazyListState()
                LaunchedEffect(needsInitialFocus, targetRow, targetRowVideo) {
                    if (needsInitialFocus && rowIndex == targetRow && targetRowVideo >= 0) rowState.scrollToItem(targetRowVideo)
                }
                Column {
                    if (row.title.isNotBlank()) {
                        Text(
                            row.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        LazyRow(
                            state = rowState,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(row.items) { i, v ->
                                ItemCard(v, viewModel, Modifier.width(rowCardWidth), requestInitialFocus = canRequestInitialFocus && rowIndex == targetRow && i == targetRowVideo, upFocusRequester = if (rowIndex == 0) tabsFocus else null, externalFocusRequester = if (rowIndex == firstRowIndex && i == firstRowCardIndex) firstCardFocus else null, onInitialFocus = { needsInitialFocus = false }, onFocused = {
                                    viewModel.prefetch(v)
                                    // Bring the row the user just entered to the top once, not on
                                    // every horizontal focus change within it.
                                    if (lastFocusedRow != rowIndex) {
                                        lastFocusedRow = rowIndex
                                        rowsScope.launch { rowsState.animateScrollToItem(rowIndex) }
                                    }
                                    if (row.canLoadMore && i >= row.items.size - 4) viewModel.loadMoreRow(rowIndex)
                                }, onLongClick = { viewModel.openMenu(v) }) {
                                    viewModel.activate(v, row.items, onPlay)
                                }
                            }
                        }
                        RowScrollCues(rowState)
                    }
                }
            }
          }
        }

        state.tab == YtTab.CHANNELS && state.subTitle == null -> ChannelsSplit(state, viewModel, onPlay, canRequestInitialFocus, firstCardFocus, feedScrollToTopTick) { needsInitialFocus = false }

        else -> {
            val gridState = rememberLazyGridState()
            LaunchedEffect(state.subTitle) {
                if (state.subTitle != null && state.grid.isNotEmpty()) gridState.scrollToItem(0)
            }
            LaunchedEffect(needsInitialFocus, targetGridIndex) {
                if (needsInitialFocus && targetGridIndex >= 0) gridState.scrollToItem(targetGridIndex)
            }
            LaunchedEffect(feedScrollToTopTick) {
                if (feedScrollToTopTick > 0) {
                    gridState.scrollToItem(0)
                    delay(80)
                    firstCardFocus?.let { runCatching { it.requestFocus() } }
                }
            }
            // Keep loading as you near the end, like an endless feed.
            LaunchedEffect(gridState, state.canLoadMore) {
                snapshotFlow {
                    val info = gridState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
                }.collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
            }
            LaunchedEffect(gridState) {
                snapshotFlow {
                    gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                }.collect { (index, offset) -> onScrollPosition(index, offset) }
            }
            // Shorts now use the same 16:9 card shape as regular videos, so the same column count.
            val gridColumnCount = columnCount
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(gridColumnCount),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                gridItemsIndexed(state.grid) { index, v ->
                    ItemCard(v, viewModel, Modifier, requestInitialFocus = canRequestInitialFocus && index == targetGridIndex, upFocusRequester = if (index < gridColumnCount) tabsFocus else null, externalFocusRequester = if (index == firstGridCardIndex) firstCardFocus else null, onInitialFocus = { needsInitialFocus = false }, onFocused = { viewModel.prefetch(v) }, onLongClick = { viewModel.openMenu(v) }) { viewModel.activate(v, state.grid, onPlay) }
                }
            }
        }
    }
}

/** Your subscribed channels as a list; the highlighted one's videos show beside it, like SmartTube. */
@Composable
private fun ChannelsSplit(state: YtUiState, viewModel: YouTubeViewModel, onPlay: (YtPlayable) -> Unit, needsInitialFocus: Boolean, firstFocus: FocusRequester? = null, scrollToTopTick: Int = 0, onInitialFocus: () -> Unit) {
    val preview by viewModel.preview.collectAsState()
    // Only OK loads a channel's videos; moving the focus just highlights the name.
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    // Picking a channel gives its videos the full width. Back brings the list back.
    var showChannelList by remember { mutableStateOf(true) }
    val firstVideoFocus = remember { FocusRequester() }
    val channelListState = rememberLazyListState()
    BackHandler(enabled = !showChannelList) { showChannelList = true }
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0 && showChannelList) {
            channelListState.scrollToItem(0)
            delay(80)
            firstFocus?.let { runCatching { it.requestFocus() } }
        }
    }
    LaunchedEffect(showChannelList, preview.items) {
        if (showChannelList) {
            if (firstFocus != null) {
                delay(80)
                runCatching { firstFocus.requestFocus() }
            }
        } else if (preview.items.isNotEmpty()) {
            delay(80)
            runCatching { firstVideoFocus.requestFocus() }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showChannelList) {
        LazyColumn(state = channelListState, modifier = Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(state.grid) { i, channel ->
                var focused by remember { mutableStateOf(false) }
                var held by remember(channel.channelId) { mutableStateOf(false) }
                var longFired by remember(channel.channelId) { mutableStateOf(false) }
                LaunchedEffect(held, longFired) {
                    if (held && !longFired) {
                        delay(550)
                        if (held && !longFired) {
                            longFired = true
                            viewModel.openMenu(channel)
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .clip(RoundedCornerShape(10.dp))
                        .border(3.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .onFocusChanged {
                            focused = it.isFocused
                            if (!it.isFocused) {
                                held = false
                                longFired = false
                            }
                            if (it.isFocused && i >= state.grid.size - 5) viewModel.loadMore()
                        }
                        // A held OK shows the channel's menu; a plain press loads its videos.
                        .onPreviewKeyEvent { e ->
                            val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (!isOk) return@onPreviewKeyEvent false
                            when (e.type) {
                                KeyEventType.KeyDown -> {
                                    if (e.nativeKeyEvent.repeatCount == 0) {
                                        held = true
                                        longFired = false
                                    }
                                }
                                KeyEventType.KeyUp -> {
                                    if (held && !longFired) {
                                        selectedChannel = channel.title
                                        showChannelList = false
                                        viewModel.previewChannel(channel)
                                    }
                                    held = false
                                    longFired = false
                                }
                            }
                            true
                        }
                        .clickable {
                            selectedChannel = channel.title
                            showChannelList = false
                            viewModel.previewChannel(channel)
                        }
                        .background(if (selectedChannel == channel.title) Color(0xFF2A2A2E) else CardBg)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = channel.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        channel.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (!showChannelList) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardBg)
                        .clickable { showChannelList = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("←  Channels", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            val gridState = rememberLazyGridState()
            val restoredIndex = preview.items.indexOfFirst { it.kind == YtKind.VIDEO && it.videoId == viewModel.lastPlayedVideoId }
            val initialIndex = if (restoredIndex >= 0) restoredIndex else preview.items.indexOfFirst { it.kind == YtKind.VIDEO }
            LaunchedEffect(needsInitialFocus, initialIndex) {
                if (needsInitialFocus && initialIndex >= 0) gridState.scrollToItem(initialIndex)
            }
            LaunchedEffect(gridState, preview.canLoadMore, preview.title) {
                snapshotFlow {
                    val info = gridState.layoutInfo
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
                }.collect { nearEnd -> if (nearEnd) viewModel.previewMore() }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(
                    YouTubeSettings.posterColumns(androidx.compose.ui.platform.LocalContext.current)
                ),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                gridItemsIndexed(preview.items) { index, v ->
                    ItemCard(v, viewModel, Modifier, requestInitialFocus = needsInitialFocus && index == initialIndex, externalFocusRequester = if (index == initialIndex) firstVideoFocus else null, onInitialFocus = onInitialFocus, onFocused = { viewModel.prefetch(v) }, onLongClick = { viewModel.openMenu(v) }) { viewModel.activate(v, preview.items, onPlay) }
                }
            }
            if (preview.loading && preview.items.isEmpty()) {
                CircularProgressIndicator(color = YtRed, modifier = Modifier.align(Alignment.Center))
            }
            if (!preview.loading && preview.items.isEmpty()) {
                Text(
                    "Choose a channel and press OK to see its videos",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        }
    }
}

@Composable
private fun SignInPanel(account: YouTubeAccountState, onCancel: () -> Unit) {
    val qr = remember(account.activateLink) {
        account.activateLink?.let { link ->
            runCatching { QrCodeGenerator.generate(link, 480, margin = 1).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            qr?.let {
                Image(it, contentDescription = "QR code to sign in", modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Sign in to OneTube", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (qr != null) "Scan the code with your phone, or go to ${account.url} and enter:"
                    else "On your phone or computer, go to ${account.url} and enter:",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
                Text(account.code.orEmpty(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("Waiting for you to approve it\u2026", color = Color.Gray, fontSize = 14.sp)
                TabChip("Cancel", selected = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SuggestionList(
    items: List<String>,
    recent: Boolean,
    first: FocusRequester,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A long history (or a long list of suggestions) scrolls instead of running off the bottom of the screen.
    Column(modifier.width(520.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (recent) "Recent searches" else "Suggestions", color = Color.Gray, fontSize = 14.sp)
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { i, text ->
                AccountRow(text, null, null, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(text) }
            }
            if (recent) item {
                AccountRow("Clear search history", null, null, Modifier) { onClearHistory() }
            }
        }
    }
}

@Composable
private fun AccountsPanel(
    account: YouTubeAccountState,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        Column(Modifier.width(520.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Who's watching?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            account.accounts.forEachIndexed { i, a ->
                AccountRow(
                    title = if (a.selected) "${a.name}   \u2713" else a.name,
                    detail = a.detail,
                    avatar = a.avatar,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                ) { onSelect(a.id) }
            }
            AccountRow("Add another account", null, null, Modifier) { onAdd() }
            AccountRow("Sign out of ${account.accountName ?: "this account"}", null, null, Modifier) { onSignOut() }
        }
    }
}

@Composable
private fun AccountRow(title: String, detail: String?, avatar: String?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
            )
        }
        Column {
            Text(title, color = Color.White, fontSize = 17.sp)
            detail?.let { Text(it, color = Color.Gray, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Message(
    title: String,
    detail: String?,
    action: String?,
    upFocusRequester: FocusRequester? = null,
    onAction: () -> Unit
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            detail?.let { Text(it, color = Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center) }
            if (action != null) TabChip(action, selected = true, upFocusRequester = upFocusRequester, onClick = onAction)
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onDownKey: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) YtRed else CardBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (upFocusRequester != null) up = upFocusRequester
                if (downFocusRequester != null) down = downFocusRequester
            }
            .onPreviewKeyEvent { event ->
                if (onDownKey != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDownKey()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

/** A round, focusable icon button, for the remote's header actions. */
@Composable
private fun IconChip(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(CardBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { if (downFocusRequester != null) down = downFocusRequester }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** The time at the start of the header, in this device's 12/24-hour form. */
@Composable
private fun HeaderClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    var time by remember(format) { mutableStateOf(format.format(Date())) }
    // Check once a second but only recompose when the shown minute actually changes.
    LaunchedEffect(format) {
        while (true) {
            val now = format.format(Date())
            if (now != time) time = now
            delay(1000)
        }
    }
    Text(
        time,
        color = Color(0xFF9A9AA5),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
    )
}

/** The account pill at the end of the header: avatar, then the name or "Sign in". */
@Composable
private fun AccountChip(
    label: String,
    signedIn: Boolean,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) Color(0xFF303036) else CardBg)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
            .focusProperties { if (downFocusRequester != null) down = downFocusRequester }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (signedIn) YtRed else Color(0xFF34343A)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (signedIn) initial else "?",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun YouTubeItemMenuDialog(
    menu: YtItemMenu,
    onClose: () -> Unit,
    onOpenChannel: () -> Unit,
    onOpenItem: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onTogglePlaylist: (YtPlaylistRow) -> Unit,
    onNewPlaylist: (String) -> Unit,
    onTogglePinned: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onNotInterested: () -> Unit,
    onBlockChannel: () -> Unit
) {
    val menuKey = menu.item.videoId ?: menu.item.channelId ?: menu.item.title
    var showPlaylists by remember(menuKey) { mutableStateOf(false) }
    var creatingPlaylist by remember(menuKey) { mutableStateOf(false) }
    var confirmingDelete by remember(menuKey) { mutableStateOf(false) }
    var newPlaylistName by remember(menuKey) { mutableStateOf("") }
    val firstRow = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(menuKey, creatingPlaylist) {
        kotlinx.coroutines.yield()
        if (creatingPlaylist) {
            runCatching { nameFocus.requestFocus() }
            keyboard?.show()
        } else {
            runCatching { firstRow.requestFocus() }
        }
    }
    val isVideo = menu.item.kind == YtKind.VIDEO
    val isChannel = menu.item.kind == YtKind.CHANNEL
    val hasChannel = menu.item.channelId != null

    if (creatingPlaylist) {
        BackHandler {
            creatingPlaylist = false
            newPlaylistName = ""
            keyboard?.hide()
        }
    }
    if (confirmingDelete) {
        BackHandler { confirmingDelete = false }
    }

    fun create() {
        if (newPlaylistName.isBlank()) return
        onNewPlaylist(newPlaylistName)
        creatingPlaylist = false
        newPlaylistName = ""
        keyboard?.hide()
    }

    NuvioDialog(
        onDismiss = onClose,
        title = if (creatingPlaylist) "New playlist" else menu.item.title,
        subtitle = when {
            creatingPlaylist -> "The video is added to it"
            // A channel card's own title is its channel name; videos carry the author.
            menu.item.kind == YtKind.CHANNEL ->
                menu.item.author?.takeIf { it.isNotBlank() } ?: menu.item.title
            else -> menu.item.author
        },
        width = 480.dp
    ) {
        if (creatingPlaylist) {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                singleLine = true,
                label = { Text("Playlist name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
            )
            MenuRow("Save to new playlist", enabled = newPlaylistName.isNotBlank() && !menu.busy) { create() }
            MenuRow("Cancel") {
                creatingPlaylist = false
                newPlaylistName = ""
                keyboard?.hide()
            }
        } else if (confirmingDelete) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Delete \"${menu.item.title}\" from your YouTube account? This can't be undone.",
                    color = Color.White,
                    fontSize = 14.sp
                )
                MenuRow("Delete playlist", firstRow, enabled = !menu.busy, onClick = onDeletePlaylist)
                MenuRow("Cancel") { confirmingDelete = false }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isVideo && !hasChannel && menu.loadingChannel) {
                    Text("Looking up the channel…", color = Color.Gray, fontSize = 14.sp)
                }
                if (isVideo && hasChannel) {
                    MenuRow("Open channel", firstRow, onClick = onOpenChannel)
                }
                if (menu.item.kind == YtKind.CHANNEL) {
                    MenuRow("Open channel", firstRow, onClick = onOpenItem)
                }
                if (menu.item.kind == YtKind.PLAYLIST) {
                    MenuRow("Open playlist", firstRow, onClick = onOpenItem)
                    MenuRow(
                        if (menu.pinned) "Unpin from the OneTube page" else "Pin to the OneTube page",
                        enabled = !menu.busy,
                        onClick = onTogglePinned
                    )
                    MenuRow("Delete playlist", enabled = !menu.busy) { confirmingDelete = true }
                }
                if ((isVideo || isChannel) && hasChannel && menu.subscribed != null) {
                    MenuRow(
                        if (menu.subscribed == true) "Unsubscribe" else "Subscribe",
                        enabled = !menu.busy,
                        onClick = onToggleSubscribe
                    )
                }
                if (isVideo) {
                    when {
                        menu.loadingPlaylists -> Text("Loading playlists…", color = Color.Gray, fontSize = 14.sp)
                        menu.playlists != null -> {
                            MenuRow(
                                if (showPlaylists) "Hide playlists" else "Add to playlist",
                                enabled = !menu.busy
                            ) { showPlaylists = !showPlaylists }
                            if (showPlaylists) {
                                menu.playlists.forEach { row ->
                                    MenuRow(
                                        (if (row.contains) "✓  " else "   ") + row.title,
                                        enabled = !menu.busy
                                    ) { onTogglePlaylist(row) }
                                }
                            }
                            MenuRow("New playlist", enabled = !menu.busy) {
                                creatingPlaylist = true
                            }
                        }
                    }
                }
                if ((isVideo || isChannel) && hasChannel) {
                    MenuRow("Block this channel", enabled = !menu.busy, onClick = onBlockChannel)
                }
                if (isVideo) {
                    MenuRow("Not interested", enabled = !menu.busy, onClick = onNotInterested)
                }
            }
        }
        menu.message?.let {
            Text(it, color = Color(0xFFFF9A9A), fontSize = 13.sp)
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // Whether the OK press currently ending began while this row had focus.
    var pressedHere by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (enabled) Color.White else Color.Gray,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0xFF34343A) else Color.Transparent)
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressedHere = false
            }
            // The OK press that opened this menu was already down when the row took focus.
            // Swallow that release so it cannot activate the row.
            .onPreviewKeyEvent { e ->
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!isOk) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) pressedHere = true
                        false
                    }
                    KeyEventType.KeyUp -> {
                        val beganHere = pressedHere
                        pressedHere = false
                        !beganHere
                    }
                    else -> false
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** Separators YouTube puts between the parts of a card's second line. */
private val YOUTUBE_INFO_SEPARATOR = Regex("\\s+[.·•|]\\s+|\\s*[•·|]\\s*|\\n")

/** Bidi and zero-width marks the engine wraps around these parts; they stop "@" checks matching. */
private val YOUTUBE_INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")

private fun String.cleanYouTubeText(): String = replace(YOUTUBE_INVISIBLE, "").trim()

private fun youtubeInfoSegments(meta: String): List<String> =
    meta.split(YOUTUBE_INFO_SEPARATOR).map { it.cleanYouTubeText() }.filter { it.isNotBlank() }

/** Case and spacing don't matter when deciding whether two info parts are the same channel. */
private fun youtubeInfoKey(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

/** A views line rather than a channel name. */
private fun looksLikeYouTubeCount(value: String): Boolean =
    value.any { it.isDigit() } && (
        value.contains("view", ignoreCase = true) ||
            value.contains("watch", ignoreCase = true) ||
            Regex("\\b\\d+(\\.\\d+)?\\s*[KMB]\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
        )

/** Whether a segment is a posted-time phrase rather than a count or channel. */
private fun looksLikeYouTubeTime(value: String): Boolean =
    value.contains("ago", ignoreCase = true) ||
        value.contains("yesterday", ignoreCase = true) ||
        value.contains("today", ignoreCase = true) ||
        Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(value)

/** Small edge chevrons, so a row with more cards off-screen says so without a "See all" button. */
@Composable
private fun BoxScope.RowScrollCues(state: LazyListState) {
    if (state.canScrollBackward) RowScrollCue(Alignment.CenterStart, Icons.AutoMirrored.Filled.KeyboardArrowLeft)
    if (state.canScrollForward) RowScrollCue(Alignment.CenterEnd, Icons.AutoMirrored.Filled.KeyboardArrowRight)
}

@Composable
private fun BoxScope.RowScrollCue(alignment: Alignment, icon: ImageVector) {
    Box(
        Modifier
            .align(alignment)
            .padding(horizontal = 4.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(23.dp)
        )
    }
}

@Composable
private fun ItemCard(item: YtItem, viewModel: YouTubeViewModel, modifier: Modifier, requestInitialFocus: Boolean = false, upFocusRequester: FocusRequester? = null, externalFocusRequester: FocusRequester? = null, onInitialFocus: () -> Unit = {}, onFocused: () -> Unit, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember(item.videoId) { mutableStateOf(false) }
    var opened by remember(item.videoId) { mutableStateOf(false) }
    // OK is held down on this poster (and whether that already counted as a long press).
    var held by remember(item.videoId) { mutableStateOf(false) }
    var longFired by remember(item.videoId) { mutableStateOf(false) }
    val longAction by rememberUpdatedState(onLongClick)
    // Some remotes (the projector included) never send key repeats, so measure the hold
    // ourselves instead of waiting for a repeated key event.
    LaunchedEffect(held, longFired) {
        if (held && !longFired && longAction != null) {
            delay(550)
            if (held && !longFired) {
                longFired = true
                longAction?.invoke()
            }
        }
    }
    // The side menu covers the page, so a preview should not keep playing behind it.
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val localFocusRequester = remember { FocusRequester() }
    val focusRequester = externalFocusRequester ?: localFocusRequester
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            kotlinx.coroutines.yield()
            runCatching { focusRequester.requestFocus() }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // What this device has played, or what your account says, whichever is further along.
    val watched = remember(item.videoId) {
        maxOf(item.percentWatched, YouTubeProgress.localPercent(context, item.videoId))
    }
    // A video that is being looked up shows a spinner, so a slow start is visible and repeated presses
    // are not needed.
    val resolving by viewModel.resolving.collectAsState()
    Column(
        modifier
            .focusRequester(focusRequester)
            .focusProperties { if (upFocusRequester != null) up = upFocusRequester }
            .clip(RoundedCornerShape(14.dp))
            .border(3.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) {
                    opened = false
                    held = false
                    longFired = false
                }
                if (it.isFocused && requestInitialFocus) onInitialFocus()
                if (it.isFocused) onFocused()
            }
            // Holding OK opens the poster's options menu; a plain press still plays it.
            .onPreviewKeyEvent { e ->
                if (onLongClick == null) return@onPreviewKeyEvent false
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!isOk) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_LONG_PRESS != 0) {
                            held = true
                            longFired = true
                            onLongClick?.invoke()
                            return@onPreviewKeyEvent true
                        }
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            held = true
                            longFired = false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        // A plain press plays the video; a long press already did its own action.
                        if (held && !longFired) {
                            opened = true
                            onClick()
                        }
                        held = false
                        longFired = false
                    }
                }
                true
            }
            .clickable { opened = true; onClick() }
            .background(if (focused) Color(0xFF2E2E34) else CardBg)
    ) {
        // Shorts keep the regular 16:9 card shape: the vertical thumbnail is letterboxed
        // instead of making the whole row taller.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = if (item.isShorts) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (focused && !opened && !sidebarExpanded && item.kind == YtKind.VIDEO && YouTubeSettings.videoPreviews(context)) {
                YouTubeThumbnailPreview(item, viewModel)
            }
            // With previews off, the focused card still needs a clear "press OK to play" cue.
            if (focused && !opened && item.kind == YtKind.VIDEO && !YouTubeSettings.videoPreviews(context)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            if (watched in 5..94 && item.kind == YtKind.VIDEO && !item.isLive) {
                Text(
                    "Resume",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            if (watched > 0 && item.kind == YtKind.VIDEO && !item.isLive) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x99000000))
                ) {
                    Box(Modifier.fillMaxWidth(if (watched >= 95) 1f else watched / 100f).height(4.dp).background(YtRed))
                }
            }
            val badge = when {
                item.isLive -> "LIVE"
                item.kind == YtKind.CHANNEL -> "CHANNEL"
                item.kind == YtKind.PLAYLIST -> "PLAYLIST"
                else -> item.badge
            }
            if (!badge.isNullOrBlank()) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(if (item.isLive) YtRed else Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (item.videoId != null && resolving == item.videoId) {
                YouTubeLoadingGlow(Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            // Keep the two-line title area reserved, but scroll a long title on one line while
            // the card has focus, like the TV YouTube app.
            Box(Modifier.height(40.dp)) {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    minLines = if (focused) 1 else 2,
                    maxLines = if (focused) 1 else 2,
                    softWrap = !focused,
                    overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (focused) Modifier.basicMarquee() else Modifier
                )
            }
            // Two info lines: the channel, then views and post time. Some feeds put the whole
            // "channel . @handle . channel . views" string in one field and add invisible bidi
            // marks around the parts, so clean and split both fields together.
            val infoSegments = buildList {
                item.author?.let { addAll(youtubeInfoSegments(it)) }
                item.meta?.let { addAll(youtubeInfoSegments(it)) }
            }
            val channel = infoSegments.firstOrNull { !it.startsWith("@") && !looksLikeYouTubeCount(it) }
            val channelKey = channel?.let(::youtubeInfoKey)
            val details = infoSegments
                .filter { !it.startsWith("@") }
                .filter { channelKey == null || youtubeInfoKey(it) != channelKey }
                .distinctBy { youtubeInfoKey(it) }
            val channelText = channel
            // History/Subscriptions and Music sometimes leave the post time or count out of the
            // second line; the engine's production date text still has them.
            val fallback = item.postTime
                ?.let(::youtubeInfoSegments)
                .orEmpty()
                .filter { !it.startsWith("@") && (looksLikeYouTubeTime(it) || looksLikeYouTubeCount(it)) }
                .filter { channelKey == null || youtubeInfoKey(it) != channelKey }
            val detailText = (details + fallback)
                .distinctBy { youtubeInfoKey(it) }
                .joinToString("  ·  ")
            if (!channelText.isNullOrBlank()) {
                Text(channelText, color = Color(0xFF9A9AA5), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                // Keep the two-line info block even when the feed has no channel line.
                Spacer(Modifier.height(16.dp))
            }
            if (detailText.isNotBlank()) {
                Text(detailText, color = Color(0xFF7E7E8A), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
