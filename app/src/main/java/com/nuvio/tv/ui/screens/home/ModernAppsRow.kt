package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.INSTALLED_APPS_CATALOG_KEY
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.InstalledAppsViewModel
import com.nuvio.tv.ui.components.AppMoveState
import com.nuvio.tv.ui.components.InstalledAppActionsDialog
import com.nuvio.tv.ui.components.InstalledAppCard
import com.nuvio.tv.ui.components.appMoveKeys
import com.nuvio.tv.ui.components.clampedStep
import com.nuvio.tv.ui.components.rememberAppMoveState
import com.nuvio.tv.ui.components.rememberHomeTile

/**
 * The launcher's Apps row inside Modern Home, kept out of `ModernHomeRows.kt` so that file stays close to
 * Nuvio One's. `ModernRowSection` calls [rememberModernAppsRow] once, then [ModernAppsRowEffects], the
 * [moveKeysModifier] and [ModernAppsRowTile] at the places the row needs them (see LAUNCHER_RULES.md).
 */
/** Where the row was scrolled to before an app moved, and the carried app's last seen index. */
internal class AppMoveHolder {
    var seenIndex = -1
    var before: Pair<Int, Int>? = null
}

internal class ModernAppsRowState(
    val row: HeroCarouselRow,
    /** Null unless this is the Apps row. */
    val viewModel: InstalledAppsViewModel?,
    val move: AppMoveState,
    val contextMenuApp: androidx.compose.runtime.MutableState<InstalledApp?>,
    val moveHolder: AppMoveHolder,
) {
    /** The apps shown in this row, in order. */
    fun shownApps(): List<InstalledApp> =
        row.items.list.mapNotNull { (it.payload as? ModernPayload.InstalledApp)?.app }

    /** An app picked up with "Move" is carried along the Apps row by the D-pad. */
    fun moveKeysModifier(rowListState: LazyListState): Modifier = Modifier.appMoveKeys(move) { delta ->
        val vm = viewModel ?: return@appMoveKeys
        val shownApps = shownApps()
        val carried = shownApps.firstOrNull { it.packageName == move.carrying } ?: return@appMoveKeys
        val from = shownApps.indexOf(carried)
        val step = clampedStep(from, delta, shownApps.lastIndex)
        if (step != 0) {
            // Remember where the row is scrolled to, to restore it when the reordered list lands.
            moveHolder.before = rowListState.firstVisibleItemIndex to rowListState.firstVisibleItemScrollOffset
            vm.move(carried, step)
        }
    }
}

/** Launcher: the Apps row. Long-press on an app opens its menu (open, rename, move, app info). */
@Composable
internal fun rememberModernAppsRow(row: HeroCarouselRow): ModernAppsRowState {
    val appsViewModel: InstalledAppsViewModel? =
        if (row.key == INSTALLED_APPS_CATALOG_KEY) androidx.hilt.navigation.compose.hiltViewModel() else null
    val contextMenuApp = remember { mutableStateOf<InstalledApp?>(null) }
    val appMove = rememberAppMoveState()
    val appMoveContext = androidx.compose.ui.platform.LocalContext.current
    val moveHolder = remember { AppMoveHolder() }
    // Rebuilt every composition: it must see the current row (its apps change as they are moved).
    val state = ModernAppsRowState(row, appsViewModel, appMove, contextMenuApp, moveHolder)
    contextMenuApp.value?.let { app ->
        val shown = row.items.list.mapNotNull { (it.payload as? ModernPayload.InstalledApp)?.app }
        val index = shown.indexOfFirst { it.packageName == app.packageName }
        if (appsViewModel == null || index < 0) contextMenuApp.value = null else InstalledAppActionsDialog(
            app = app,
            canMove = shown.size > 1,
            onOpen = { appsViewModel.launch(app) },
            onMove = {
                appMove.pickUp(app.packageName)
                android.widget.Toast.makeText(appMoveContext, R.string.app_move_hint, android.widget.Toast.LENGTH_SHORT).show()
            },
            onAppInfo = { appsViewModel.openAppInfo(app) },
            onRename = { appsViewModel.rename(app, it) },
            iconChooser = appsViewModel.iconChooser(app),
            onSetHidden = { appsViewModel.setHidden(app, it) },
            onSetHiddenOnPage = { appsViewModel.setHiddenOnPage(app, it) },
            onDismiss = { contextMenuApp.value = null }
        )
    }
    return state
}

/**
 * While an app is carried along the Apps row, keep it on screen and give it the focus after each step.
 * Without this the focus ring stayed on whichever tile sat in the same place on screen (a neighbour), not on the
 * app being moved, and the moved app could be partly off the edge.
 */
@Composable
internal fun ModernAppsRowEffects(
    apps: ModernAppsRowState,
    rowListState: LazyListState,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
) {
    val row = apps.row
    val appMove = apps.move
    val moveHolder = apps.moveHolder
    val carriedAppIndex = row.items.list.indexOfFirst {
        (it.payload as? ModernPayload.InstalledApp)?.app?.packageName == appMove.carrying
    }
    // Moving an app: the carried tile keeps the focus (see ModernAppsRowTile), the reordered list lands a few frames
    // after the key press, and the row keeps its scroll position by index when it does. Left alone the row
    // re-anchors on the moved tile, which made every other tile jump a slot (and the focus used to go to the
    // neighbour first, scrolling the row one tile, before the reorder arrived: the smooth-then-snap on each step).
    androidx.compose.runtime.SideEffect {
        if (appMove.carrying == null) {
            moveHolder.seenIndex = -1
            moveHolder.before = null
        } else if (carriedAppIndex != moveHolder.seenIndex) {
            val before = moveHolder.before
            if (moveHolder.seenIndex >= 0 && before != null) rowListState.requestScrollToItem(before.first, before.second)
            moveHolder.seenIndex = carriedAppIndex
        }
    }
    LaunchedEffect(appMove.carrying, carriedAppIndex) {
        if (appMove.carrying == null || carriedAppIndex < 0) return@LaunchedEffect
        // The row's focus index follows the app (it is where the carried tile is now).
        onRowItemFocused(row.key, carriedAppIndex, false)
        // Keep it on screen with one smooth scroll by however much of it sticks out.
        val info = rowListState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == carriedAppIndex }
        val end = info.viewportEndOffset - info.afterContentPadding
        when {
            item == null -> rowListState.scrollToItem(carriedAppIndex)
            item.offset + item.size > end -> rowListState.animateScrollBy((item.offset + item.size - end).toFloat())
            item.offset < info.viewportStartOffset -> rowListState.animateScrollBy((item.offset - info.viewportStartOffset).toFloat())
        }
    }
}

/** One app in the Apps row. */
@Composable
internal fun LazyItemScope.ModernAppsRowTile(
    apps: ModernAppsRowState,
    payload: ModernPayload.InstalledApp,
    isTargetItem: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    posterCardCornerRadius: Dp,
) {
    val appMove = apps.move
    // While an app is carried only that app takes the focus: the row's own focus index changes
    // before the reordered list arrives, and the neighbour used to be given the focus then.
    val carryingNow = appMove.carrying
    val wantsFocus = if (carryingNow != null) carryingNow == payload.app.packageName else isTargetItem
    LaunchedEffect(wantsFocus) {
        if (wantsFocus) runCatching { requester.requestFocus() }
    }
    val appsVm = apps.viewModel
    if (appsVm != null) {
        InstalledAppCard(
            app = payload.app,
            loadImage = appsVm::image,
            onClick = {
                onFocused()
                appsVm.launch(payload.app)
            },
            onLongClick = { apps.contextMenuApp.value = payload.app },
            modifier = Modifier
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() },
            width = rememberHomeTile()?.width ?: 160.dp,
            showLabel = false,
            // The same corner as the poster cards in this row (the setting), so the tiles match.
            cornerRadius = posterCardCornerRadius,
            moving = appMove.carrying == payload.app.packageName,
            quietFocus = appMove.carrying != null && appMove.carrying != payload.app.packageName,
            // Tiles slide to their new place when the order changes (no fade in or out).
            rootModifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
        )
    }
}
