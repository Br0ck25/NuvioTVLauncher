package com.nuvio.tv.core.launcher

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.nuvio.tv.ui.screens.ambient.AmbientIdleController
import com.nuvio.tv.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow

/** Lets [LauncherHost] reach the Button Mapper without `MainActivity` needing an `@Inject` field for it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LauncherHostEntryPoint {
    fun buttonMapperProvider(): Lazy<ButtonKeyEventHandler>
}

/**
 * Everything the launcher needs from the activity, kept out of `MainActivity.kt` so that file stays
 * close to Nuvio One's and merges cleanly. `MainActivity` owns one of these and forwards its lifecycle
 * and key callbacks; see `LAUNCHER_RULES.md`.
 *
 * Holds: Home key and Apps key handling, the Home/Apps/mapped-route requests Navigation observes, the
 * Home role offer, and the Button Mapper's foreground hooks.
 *
 * Create it as an activity property (it registers an activity-result launcher, which must happen before
 * the activity is STARTED).
 */
class LauncherHost(
    private val activity: ComponentActivity,
    /** Lazy, because `MainActivity` declares its ambient controller after this property. */
    private val ambient: () -> AmbientIdleController,
) {
    private val buttonMapperProvider: Lazy<ButtonKeyEventHandler> by lazy {
        EntryPointAccessors.fromApplication(
            activity.applicationContext,
            LauncherHostEntryPoint::class.java
        ).buttonMapperProvider()
    }

    internal val pendingMappedRouteRequest = MutableStateFlow<String?>(null)
    internal val pendingMappedRouteNonce = MutableStateFlow(0)

    /**
     * Incremented on every HOME request (the Home key via the Home app role or the launcher's
     * accessibility service, or a plain HOME-category launch). Navigation observes it and returns to the
     * top of Home.
     */
    internal val pendingHomeRequestNonce = MutableStateFlow(0)

    /** Set when Home is double-tapped; consumed once the profile switcher is shown. */
    internal val pendingProfileSwitchRequest = MutableStateFlow(false)

    /** Bumped when the remote's Apps button (or an ALL_APPS intent) asks for the Apps page. */
    internal val pendingOpenAppsNonce = MutableStateFlow(0)

    /** Single vs. double tap detection for Home pressed while the launcher is in front. */
    private val homeKeyGesture = HomeKeyGesture()

    /**
     * True while this app is the device's launcher: the system's Home app, or the accessibility
     * override is on. Then Back must never leave the app, and Home is handled here.
     */
    @Volatile
    var handlesHomeKey = false
        private set

    /** One-time offer of the native Home role after an update; the result is re-checked, never trusted. */
    private val homeRoleOfferLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        LauncherSetup.recordRoleRequestResult(activity)
        refreshHomeRole()
    }

    // ---- Lifecycle ----

    /** A first launch from the Home button counts as the first press (a restore does not); the Home role is re-read. */
    fun onCreate(savedInstanceState: Bundle?, intent: Intent?) {
        if (savedInstanceState == null) {
            captureHomeRequestIntent(intent, fromNewIntent = false)
            captureAppsRequest(intent)
        }
        refreshHomeRole()
    }

    fun onResume() {
        refreshHomeRole()
        LauncherSetup.takePendingRoleOffer(activity)?.let { offer ->
            runCatching { homeRoleOfferLauncher.launch(offer) }
                .onFailure { LauncherSetup.recordRoleRequestResult(activity, launchFailed = true) }
        }
        HomeForegroundDelivery.attach(this, ::handleHomeKeyPress)
        // After the first frame, so the Button Mapper is never built on the way to the launcher showing.
        activity.window?.decorView?.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching {
                buttonMapperProvider.get().actionExecutor.attachForegroundHandler(activity, foregroundButtonHandler)
            }
        }
    }

    fun onNewIntent(intent: Intent?) {
        captureHomeRequestIntent(intent)
        captureAppsRequest(intent)
    }

    fun onPause() {
        HomeForegroundDelivery.detach(this)
        detachMapper()
    }

    fun onDestroy() {
        detachMapper()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) builtMapper()?.cancelPendingGestures()
    }

    /** As the launcher, Back never leaves the app and there is no "press back again to exit" prompt. */
    @Suppress("FunctionOnlyReturningConstant")
    fun suppressAppExit(): Boolean = true

    private fun detachMapper() {
        builtMapper()?.let {
            it.actionExecutor.detachForegroundHandler(activity)
            it.cancelPendingGestures()
        }
    }

    // ---- Keys ----

    /**
     * First look at a key event, before the ambient handler. Returns true when the launcher consumed it:
     * the Button Mapper while it is learning a button, the remote's Apps button, or the Home key.
     */
    fun onDispatchKeyEvent(event: KeyEvent): Boolean {
        // Button Mapper: while the user is teaching it a button (Settings), that press belongs to it.
        val mapper = builtMapper()
        if (mapper != null && mapper.captureController.isListening()) {
            if (forwardToMapper(mapper, event)) return true
        }
        // The remote's Apps button opens the launcher's own Apps page instead of the TV's app list.
        if (AppsKeyStore.matches(activity, event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                ambient().dismiss()
                pendingOpenAppsNonce.value += 1
            }
            return true
        }
        // HOME is checked before the ambient handler: while the slideshow is up a Home press should end it
        // and go home. Some firmware delivers raw HOME keys; most send a HOME intent or let the
        // accessibility service filter the key, so this is a fallback, not the only path.
        if (event.keyCode == KeyEvent.KEYCODE_HOME) refreshHomeRole()
        if (event.keyCode == KeyEvent.KEYCODE_HOME && handlesHomeKey) {
            val idle = ambient()
            idle.onInteraction()
            if (event.action == KeyEvent.ACTION_DOWN && idle.isShowing.value) {
                idle.dismiss()
            }
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                handleHomeKeyPress(event.eventTime)
            }
            return true
        }
        return false
    }

    /** Last look at a key event: a remote button the user gave actions to (only when it has an enabled mapping). */
    fun onMappedKeyEvent(event: KeyEvent): Boolean {
        val mapper = builtMapper() ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME) return false
        if (mapper.repository.activeMappingFor(event.keyCode, isGlobal = false) == null) return false
        return forwardToMapper(mapper, event)
    }

    private fun forwardToMapper(mapper: ButtonKeyEventHandler, event: KeyEvent): Boolean =
        mapper.onKeyEvent(
            event = event,
            source = ButtonEventSource.FOREGROUND,
            accessibilityEnabled = LauncherAccessibilityService.isServiceEnabled(activity),
            nativeHomeActive = LauncherSetup.isDefaultHome(activity)
        )

    // ---- Home / Apps / mapped-button requests ----

    /** Home pressed while the launcher is already in front: a single tap goes to the top of Home, two open the profile switcher. */
    private fun handleHomeKeyPress(pressedAtMs: Long) {
        // A Home button the user changed in Button Mapper runs their actions; otherwise it is the usual
        // tap (top of Home) / double tap (profiles).
        val mapper = customHomeMapper()
        if (mapper != null) {
            mapper.onHomePress(pressedAtMs = pressedAtMs, source = ButtonEventSource.FOREGROUND)
            return
        }
        applyHomeKeyPress(homeKeyGesture.onPress(pressedAtMs))
    }

    /** The Button Mapper, once it has been built; it is never built by asking. */
    private fun builtMapper(): ButtonKeyEventHandler? = ButtonKeyEventHandler.currentInstance()

    /** The Button Mapper, but only when the user has changed what the Home button does. */
    private fun customHomeMapper(): ButtonKeyEventHandler? {
        val mapper = builtMapper() ?: return null
        val mapping = mapper.repository.activeMappingFor(KeyEvent.KEYCODE_HOME, isGlobal = false) ?: return null
        return if (mapping.matchesFactoryHomeDefaults()) null else mapper
    }

    /** What a mapped button asks the launcher to do while it is in front. */
    private val foregroundButtonHandler = object : ForegroundButtonActionHandler {
        override fun onExecuteAction(action: ButtonAction): Boolean = executeForegroundButtonAction(action)
    }

    private fun openMappedRoute(route: String) {
        pendingMappedRouteRequest.value = route
        pendingMappedRouteNonce.value += 1
    }

    private fun executeForegroundButtonAction(action: ButtonAction): Boolean {
        ambient().dismiss()
        return when (action) {
            ButtonAction.GoToTopOfHome -> {
                pendingHomeRequestNonce.value += 1
                true
            }
            ButtonAction.OpenProfileSwitcher -> {
                pendingHomeRequestNonce.value += 1
                pendingProfileSwitchRequest.value = true
                true
            }
            ButtonAction.OpenHome -> { openMappedRoute(Screen.Home.route); true }
            ButtonAction.OpenSearch -> { openMappedRoute(Screen.Search.route); true }
            ButtonAction.OpenLibrary -> { openMappedRoute(Screen.Library.route); true }
            ButtonAction.OpenSettings -> { openMappedRoute(Screen.Settings.route); true }
            ButtonAction.OpenApps,
            ButtonAction.OpenAppDrawer -> {
                pendingOpenAppsNonce.value += 1
                true
            }
            ButtonAction.OpenTvGuide -> { openMappedRoute(Screen.LiveTv.route); true }
            ButtonAction.OpenLiveTv -> {
                com.nuvio.tv.livetv.calendar.CalendarNav.openLiveTv()
                true
            }
            else -> false
        }
    }

    private fun applyHomeKeyPress(press: HomeKeyPress, openProfileSelection: Boolean = false) {
        if (press == HomeKeyPress.IGNORED) return
        ambient().dismiss()
        pendingHomeRequestNonce.value += 1
        if (press == HomeKeyPress.DOUBLE || openProfileSelection) {
            pendingProfileSwitchRequest.value = true
        }
    }

    private fun captureAppsRequest(intent: Intent?) {
        if (LauncherHomeRequest.isAppsRequest(intent)) {
            ambient().dismiss()
            pendingOpenAppsNonce.value += 1
        }
    }

    private fun refreshHomeRole() {
        handlesHomeKey = runCatching { LauncherSetup.handlesHome(activity) }.getOrDefault(false)
    }

    /**
     * Raw keys, native HOME launches and accessibility requests all use the same tap detector. A cold
     * native HOME launch records the first press but leaves startup/onboarding alone.
     */
    private fun captureHomeRequestIntent(intent: Intent?, fromNewIntent: Boolean = true) {
        // A mapped button pressed in another app asked the launcher to do something (open Search ...).
        if (!intent?.getStringExtra(AndroidButtonActionPlatformBridge.EXTRA_BUTTON_MAPPER_ACTION).isNullOrBlank()) {
            val handled = runCatching {
                buttonMapperProvider.get().onActivityIntent(intent, SystemClock.uptimeMillis(), fromNewIntent)
            }.getOrDefault(false)
            if (handled) return
        }
        val press = LauncherHomeRequest.onIntent(
            intent = intent,
            gesture = homeKeyGesture,
            nowMs = SystemClock.uptimeMillis(),
            fromNewIntent = fromNewIntent
        )
        applyHomeKeyPress(press, LauncherHomeRequest.isProfileSelectionRequested(intent))
    }
}
