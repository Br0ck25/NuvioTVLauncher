package com.nuvio.tv.ui.screens.setup

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nuvio.tv.core.launcher.LauncherHost
import com.nuvio.tv.core.launcher.LauncherSetup
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.home.HomeViewModel

/**
 * The launcher's Compose hooks for `MainActivity`, kept here so `MainActivity.kt` only needs a call to each.
 * See `LAUNCHER_RULES.md`.
 */

/**
 * The first-run setup (Home app, Home button, notifications) comes before sign-in. An update can quietly
 * switch the Home button override off: if it was on and is off now, the setup reopens at the checklist with
 * a notice instead of leaving Home on the stock launcher.
 *
 * Draws the wizard while it is needed and returns true; the caller shows nothing else then.
 */
@Composable
fun LauncherSetupGate(activity: ComponentActivity): Boolean {
    var showLauncherSetup by remember {
        LauncherSetup.checkOverrideLost(activity)
        mutableStateOf(!LauncherSetupWizardPrefs.isDone(activity))
    }
    if (showLauncherSetup) {
        LauncherSetupWizard(
            onFinished = {
                LauncherSetupWizardPrefs.markDone(activity)
                showLauncherSetup = false
            }
        )
    }
    return showLauncherSetup
}

/**
 * Navigation reacts to the launcher's requests: the Home key / a HOME launch always lands on Home, scrolled
 * to the top, whatever screen (tab, detail page, search, settings, player) is showing; the Apps button opens
 * the Apps page; a remote button mapped to open a page (Search, Library, Settings, the TV guide) goes there.
 */
@Composable
fun LauncherNavigationEffects(
    host: LauncherHost,
    navController: NavHostController,
    layoutChosen: Boolean?,
    actualRoute: String?,
) {
    val homeRequestNonce by host.pendingHomeRequestNonce.collectAsStateWithLifecycle()
    var pendingHomeScrollReset by remember { mutableStateOf(false) }
    LaunchedEffect(homeRequestNonce, layoutChosen) {
        if (homeRequestNonce == 0 || layoutChosen != true) return@LaunchedEffect
        if (navController.currentDestination?.route == Screen.Home.route) {
            requestHomeScrollToTop(navController)
            return@LaunchedEffect
        }
        // Reset only once Home is the current destination; Home restores its own saved
        // scroll position, so the reset has to land after that restore.
        pendingHomeScrollReset = true
        val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
        if (!popped) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val openAppsNonce by host.pendingOpenAppsNonce.collectAsStateWithLifecycle()
    LaunchedEffect(openAppsNonce, layoutChosen) {
        if (openAppsNonce == 0 || layoutChosen != true) return@LaunchedEffect
        if (navController.currentDestination?.route == Screen.Apps.route) return@LaunchedEffect
        navController.navigate(Screen.Apps.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val mappedRoute by host.pendingMappedRouteRequest.collectAsStateWithLifecycle()
    val mappedRouteNonce by host.pendingMappedRouteNonce.collectAsStateWithLifecycle()
    LaunchedEffect(mappedRoute, mappedRouteNonce, layoutChosen) {
        val targetRoute = mappedRoute ?: return@LaunchedEffect
        if (layoutChosen != true) return@LaunchedEffect
        host.pendingMappedRouteRequest.value = null
        if (navController.currentDestination?.route == targetRoute) return@LaunchedEffect
        if (!navController.popBackStack(targetRoute, inclusive = false)) {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    LaunchedEffect(pendingHomeScrollReset, actualRoute) {
        if (!pendingHomeScrollReset || actualRoute != Screen.Home.route) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        requestHomeScrollToTop(navController)
        pendingHomeScrollReset = false
    }
}

/**
 * Double-tapping Home asks for the profile switcher. Consumed on read so a later recomposition (or
 * returning from the switcher) cannot repeat it. [onSwitch] decides whether switching makes sense.
 */
@Composable
fun LauncherProfileSwitchEffect(host: LauncherHost, onSwitch: () -> Unit) {
    val profileSwitchRequested by host.pendingProfileSwitchRequest.collectAsStateWithLifecycle()
    LaunchedEffect(profileSwitchRequested) {
        if (!profileSwitchRequested) return@LaunchedEffect
        host.pendingProfileSwitchRequest.value = false
        onSwitch()
    }
}

/**
 * Asks Home to drop its saved scroll/focus state and scroll back to the first row. Safe to call when Home
 * is not the current destination: the reset is stored on the Home ViewModel and applied the next time its
 * content is composed.
 */
private fun requestHomeScrollToTop(navController: NavHostController) {
    val homeEntry = try {
        navController.getBackStackEntry(Screen.Home.route)
    } catch (_: IllegalArgumentException) {
        // "home" not yet on the back stack (e.g. nav graph not fully initialized).
        return
    }
    // HomeViewModel is @HiltViewModel, so it must come from Hilt's factory: a plain
    // ViewModelProvider(entry) only works if the Home screen already created the instance.
    var context = navController.context
    while (context is android.content.ContextWrapper && context !is androidx.activity.ComponentActivity) {
        context = context.baseContext
    }
    val activity = context as? androidx.activity.ComponentActivity ?: return
    val factory = dagger.hilt.android.internal.lifecycle.HiltViewModelFactory.createInternal(
        activity,
        homeEntry,
        homeEntry.arguments,
        homeEntry.defaultViewModelProviderFactory,
    )
    androidx.lifecycle.ViewModelProvider(
        homeEntry.viewModelStore,
        factory,
        homeEntry.defaultViewModelCreationExtras,
    )[HomeViewModel::class.java].requestScrollToTop()
}
