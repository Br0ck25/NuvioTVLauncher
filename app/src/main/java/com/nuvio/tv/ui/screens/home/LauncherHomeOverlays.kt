package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Launcher: settings, notifications and TV inputs at the top right of Home, and the two panels they open.
 * `HomeScreen` makes one call to this from inside its content `Box`; the state lives here, not in `HomeScreen`.
 * Home (the Home key, [scrollToTopTrigger]) closes the panels.
 */
@Composable
fun BoxScope.LauncherHomeOverlays(
    scrollToTopTrigger: Int,
    showHomeLoader: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    var showSettingsDrawer by rememberSaveable { mutableStateOf(false) }
    var showNotificationsDrawer by rememberSaveable { mutableStateOf(false) }

    var handledPanelCloseTrigger by rememberSaveable { mutableStateOf(scrollToTopTrigger) }
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger != handledPanelCloseTrigger) {
            handledPanelCloseTrigger = scrollToTopTrigger
            showSettingsDrawer = false
            showNotificationsDrawer = false
        }
    }

    if (!showHomeLoader) {
        HomeTopBarHeader(
            onNotificationsClick = { showNotificationsDrawer = true },
            onSettingsClick = { showSettingsDrawer = true },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
    if (showSettingsDrawer) {
        HomeSettingsDrawer(
            onDismiss = { showSettingsDrawer = false },
            onOpenNuvioSettings = onNavigateToSettings
        )
    }
    if (showNotificationsDrawer) {
        NotificationsDrawer(onDismiss = { showNotificationsDrawer = false })
    }
}
