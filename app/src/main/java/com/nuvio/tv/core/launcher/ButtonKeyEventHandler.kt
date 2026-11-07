package com.nuvio.tv.core.launcher

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ButtonMapperEntryPoint {
    fun buttonKeyEventHandler(): ButtonKeyEventHandler
    fun buttonMappingRepository(): ButtonMappingRepository
    fun buttonCaptureController(): ButtonCaptureController
    fun buttonActionExecutor(): ButtonActionExecutor
}

/**
 * Shared key and Home event router connecting foreground (`MainActivity`) and global
 * (`LauncherAccessibilityService`) event sources to [ButtonCaptureController],
 * [ButtonMappingRepository], [ButtonMappingEngine], and [ButtonActionExecutor].
 *
 * Ensures there is a single authoritative gesture detector and action-resolution path
 * for both Home and all other remote buttons.
 */
@Singleton
class ButtonKeyEventHandler(
    val repository: ButtonMappingRepository,
    val captureController: ButtonCaptureController,
    val actionExecutor: ButtonActionExecutor,
    scheduler: ButtonGestureScheduler = CoroutineButtonGestureScheduler(),
    homeKeyGesture: HomeKeyGesture = HomeKeyGesture()
) {
    @Inject
    constructor(
        repository: ButtonMappingRepository,
        captureController: ButtonCaptureController,
        actionExecutor: ButtonActionExecutor
    ) : this(
        repository = repository,
        captureController = captureController,
        actionExecutor = actionExecutor,
        scheduler = CoroutineButtonGestureScheduler(),
        homeKeyGesture = HomeKeyGesture()
    )

    val engine: ButtonMappingEngine = ButtonMappingEngine(
        homeKeyGesture = homeKeyGesture,
        scheduler = scheduler,
        timingConfig = repository.currentSnapshot().timingConfig,
        onActionResolved = { gesture ->
            actionExecutor.execute(gesture.action)
        }
    )

    init {
        activeInstance.set(this)
    }

    /**
     * Handles an Android [KeyEvent] from either `MainActivity.dispatchKeyEvent` (`FOREGROUND`)
     * or `LauncherAccessibilityService.onKeyEvent` (`GLOBAL`).
     *
     * @return `true` if the event was consumed by capture or an active button mapping,
     *         `false` if normal system/activity handling should proceed.
     */
    fun onKeyEvent(
        event: KeyEvent,
        source: ButtonEventSource,
        accessibilityEnabled: Boolean = source == ButtonEventSource.GLOBAL,
        nativeHomeActive: Boolean = false
    ): Boolean = onKeyEvent(
        keyCode = event.keyCode,
        action = event.action,
        repeatCount = event.repeatCount,
        isCanceled = event.isCanceled,
        eventTimeMs = event.eventTime,
        source = source,
        accessibilityEnabled = accessibilityEnabled,
        nativeHomeActive = nativeHomeActive
    )

    /**
     * Primitive overload of [onKeyEvent] for deterministic unit testing.
     */
    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
        isCanceled: Boolean = false,
        eventTimeMs: Long,
        source: ButtonEventSource,
        accessibilityEnabled: Boolean = source == ButtonEventSource.GLOBAL,
        nativeHomeActive: Boolean = false
    ): Boolean {
        // 1. If the user is actively capturing a button in "Add Button Mapping", offer it first.
        if (captureController.isListening()) {
            val captured = captureController.onKeyEvent(
                keyCode = keyCode,
                action = action,
                repeatCount = repeatCount,
                source = source,
                repository = repository,
                accessibilityEnabled = accessibilityEnabled,
                nativeHomeActive = nativeHomeActive
            )
            if (captured) {
                engine.cancelAll()
                return true
            }
        }

        // 2. Never intercept system-reserved keys (Volume, Power, D-pad, OK/Enter).
        if (!RemoteButtonCatalog.isMappableKey(keyCode)) {
            return false
        }

        // 3. Record actual observed key capability without blocking input handling.
        if ((action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) && repeatCount == 0) {
            repository.recordKeyObserved(
                keyCode = keyCode,
                isGlobal = source == ButtonEventSource.GLOBAL
            )
        }

        // 4. Resolve active mapping for this key and scope from in-memory repository state.
        val snapshot = repository.currentSnapshot()
        engine.timingConfig = snapshot.timingConfig
        val mapping = repository.activeMappingFor(
            keyCode = keyCode,
            isGlobal = source == ButtonEventSource.GLOBAL
        ) ?: return false

        return engine.onKeyEvent(
            keyCode = keyCode,
            action = action,
            repeatCount = repeatCount,
            isCanceled = isCanceled,
            eventTimeMs = eventTimeMs,
            source = source,
            mapping = mapping
        )
    }

    /**
     * Handles a Home press timestamp delivered via `HomeForegroundDelivery` or foreground Home fallback.
     */
    fun onHomePress(
        pressedAtMs: Long,
        forceDoublePress: Boolean = false,
        source: ButtonEventSource = ButtonEventSource.FOREGROUND
    ): HomeKeyPress {
        val snapshot = repository.currentSnapshot()
        engine.timingConfig = snapshot.timingConfig
        val mapping = repository.activeMappingFor(
            keyCode = KeyEvent.KEYCODE_HOME,
            isGlobal = source == ButtonEventSource.GLOBAL
        )
        return engine.onHomePress(
            pressedAtMs = pressedAtMs,
            mapping = mapping,
            forceDoublePress = forceDoublePress,
            source = source
        )
    }

    /**
     * Handles an Activity launch or `onNewIntent` that may carry either an explicit
     * `EXTRA_BUTTON_MAPPER_ACTION` (from a global button action) or a native/accessibility
     * Home request (`CATEGORY_HOME` or `EXTRA_GO_HOME`).
     *
     * @return `true` if a button mapper or Home action was handled.
     */
    fun onActivityIntent(
        intent: Intent?,
        nowMs: Long,
        fromNewIntent: Boolean
    ): Boolean {
        if (intent == null) return false

        // 1. Check if launched globally by ButtonActionExecutor with a specific Nuvio action.
        val explicitActionKey = intent.getStringExtra(
            AndroidButtonActionPlatformBridge.EXTRA_BUTTON_MAPPER_ACTION
        )
        if (!explicitActionKey.isNullOrBlank()) {
            intent.removeExtra(AndroidButtonActionPlatformBridge.EXTRA_BUTTON_MAPPER_ACTION)
            intent.removeExtra(LauncherHomeRequest.EXTRA_GO_HOME)
            intent.removeExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS)
            intent.removeExtra(LauncherHomeRequest.EXTRA_OPEN_PROFILE_SELECTION)
            val action = ButtonAction.fromStorageKey(explicitActionKey)
            if (action != ButtonAction.None) {
                actionExecutor.execute(action)
                return true
            }
        }

        // 2. Otherwise process native CATEGORY_HOME or accessibility EXTRA_GO_HOME requests
        // through the single authoritative Home gesture path.
        if (!LauncherHomeRequest.isRequested(intent)) return false

        val explicit = intent.getBooleanExtra(LauncherHomeRequest.EXTRA_GO_HOME, false)
        val pressedAt = LauncherHomeRequest.pressUptimeMs(intent, nowMs)
        val forceProfile = LauncherHomeRequest.isProfileSelectionRequested(intent)
        intent.removeExtra(LauncherHomeRequest.EXTRA_GO_HOME)
        intent.removeExtra(LauncherHomeRequest.EXTRA_HOME_PRESS_UPTIME_MS)
        intent.removeExtra(LauncherHomeRequest.EXTRA_OPEN_PROFILE_SELECTION)

        if (!fromNewIntent && !explicit) {
            engine.primeFirstHomePress(pressedAt)
            if (forceProfile) {
                actionExecutor.execute(ButtonAction.OpenProfileSwitcher)
                return true
            }
            return false
        }

        val press = onHomePress(
            pressedAtMs = pressedAt,
            forceDoublePress = forceProfile,
            source = ButtonEventSource.INTENT
        )
        return press != HomeKeyPress.IGNORED
    }

    /**
     * Cancels any pending multi-tap or long-press timers (e.g., when Activity loses focus or pauses).
     */
    fun cancelPendingGestures() {
        engine.cancelAll()
    }

    companion object {
        private val activeInstance = AtomicReference<ButtonKeyEventHandler?>(null)

        fun currentInstance(): ButtonKeyEventHandler? = activeInstance.get()

        fun fromContext(context: Context): ButtonKeyEventHandler? {
            activeInstance.get()?.let { return it }
            return runCatching {
                val appContext = context.applicationContext ?: context
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    ButtonMapperEntryPoint::class.java
                )
                entryPoint.buttonKeyEventHandler()
            }.getOrNull()
        }
    }
}
