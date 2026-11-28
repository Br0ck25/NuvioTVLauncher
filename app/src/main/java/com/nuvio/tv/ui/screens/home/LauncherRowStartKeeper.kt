package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Keeps a Home row on its first card when new items arrive in front of it.
 *
 * A LazyRow with keyed items holds the first visible card in place by its key, so when a refresh puts new items
 * before it (the usual case for "Recently Added") the row ends up scrolled past them and no longer starts at
 * the beginning. A row that was at its start stays at its start. A row the user had scrolled into, or the row
 * the remote is on right now, is left alone.
 */
@Composable
internal fun LauncherKeepRowAtStart(
    listState: LazyListState,
    firstItemKey: String?,
    isActiveRow: () -> Boolean
) {
    val tracker = remember { StartTracker() }
    // Runs while the new list is composed, before the row is laid out again, so the state still describes the old list.
    if (tracker.key != firstItemKey) {
        val atStart = Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
        tracker.reset = tracker.key != null && firstItemKey != null && atStart
        tracker.key = firstItemKey
    }
    LaunchedEffect(firstItemKey) {
        if (!tracker.reset) return@LaunchedEffect
        tracker.reset = false
        if (!isActiveRow()) runCatching { listState.scrollToItem(0, 0) }
    }
}

private class StartTracker {
    var key: String? = null
    var reset = false
}
