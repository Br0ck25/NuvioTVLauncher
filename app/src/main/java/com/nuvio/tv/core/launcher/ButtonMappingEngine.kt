package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Source of a key event for foreground vs. global deduplication and scope checks.
 */
enum class ButtonEventSource {
    FOREGROUND,
    GLOBAL,
    INTENT
}

/**
 * Resolved gesture emission from [ButtonMappingEngine].
 */
data class ResolvedButtonGesture(
    val keyCode: Int,
    val pressType: ButtonPressType,
    val action: ButtonAction,
    val source: ButtonEventSource,
    val timestampMs: Long
)

/**
 * Abstraction for scheduling delayed gesture callbacks (long-press threshold and double-click window).
 */
interface ButtonGestureScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): CancellableTask

    interface CancellableTask {
        fun cancel()
    }
}

/**
 * Production scheduler backed by a [CoroutineScope].
 */
class CoroutineButtonGestureScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ButtonGestureScheduler {
    override fun schedule(delayMs: Long, block: () -> Unit): ButtonGestureScheduler.CancellableTask {
        val job: Job = scope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            block()
        }
        return object : ButtonGestureScheduler.CancellableTask {
            override fun cancel() {
                job.cancel()
            }
        }
    }
}

/**
 * Gesture detection and action resolution engine for Nuvio TV Launcher's Button Mapper.
 *
 * Key responsibilities:
 * - Detects single-click, double-click (~450 ms window), and long-press (~600 ms threshold).
 * - Suppresses key-repeat events (`repeatCount > 0`).
 * - Matches `ACTION_DOWN` and `ACTION_UP` and handles missing `ACTION_UP` or canceled events.
 * - Tracks multiple buttons independently without cross-key interference.
 * - Deduplicates events between foreground (`MainActivity`) and global (`LauncherAccessibilityService`)
 *   handlers within `minDuplicateGapMs` (120 ms).
 * - Preserves Nuvio's immediate first-press and double-click Home behavior by delegating Home
 *   short-press timing to the authoritative [HomeKeyGesture] detector while adding long-press and
 *   customizable action resolution.
 */
class ButtonMappingEngine(
    private val homeKeyGesture: HomeKeyGesture = HomeKeyGesture(),
    private val scheduler: ButtonGestureScheduler = CoroutineButtonGestureScheduler(),
    timingConfig: ButtonMapperTimingConfig = ButtonMapperTimingConfig(),
    private val onActionResolved: (ResolvedButtonGesture) -> Unit = {}
) {
    private val lock = Any()

    @Volatile
    var timingConfig: ButtonMapperTimingConfig = timingConfig

    private class KeyState(
        var isDown: Boolean = false,
        var downTimeMs: Long = Long.MIN_VALUE,
        var downSource: ButtonEventSource? = null,
        var longPressFired: Boolean = false,
        var longPressTask: ButtonGestureScheduler.CancellableTask? = null,
        var pendingTapCount: Int = 0,
        var firstReleaseTimeMs: Long = Long.MIN_VALUE,
        var singleTapTask: ButtonGestureScheduler.CancellableTask? = null,
        var lastHandledDownTimeMs: Long = Long.MIN_VALUE,
        var lastHandledDownSource: ButtonEventSource? = null,
        var lastHandledUpTimeMs: Long = Long.MIN_VALUE,
        var lastHandledUpSource: ButtonEventSource? = null
    ) {
        fun cancelLongPress() {
            longPressTask?.cancel()
            longPressTask = null
        }

        fun cancelSingleTap() {
            singleTapTask?.cancel()
            singleTapTask = null
            pendingTapCount = 0
            firstReleaseTimeMs = Long.MIN_VALUE
        }

        fun resetAll() {
            cancelLongPress()
            cancelSingleTap()
            isDown = false
            downTimeMs = Long.MIN_VALUE
            downSource = null
            longPressFired = false
        }
    }

    private val keyStates = HashMap<Int, KeyState>()

    /**
     * Processes a raw key event (either `ACTION_DOWN` or `ACTION_UP`).
     *
     * @param keyCode Android `KeyEvent.KEYCODE_*` constant.
     * @param action `KeyEvent.ACTION_DOWN` or `KeyEvent.ACTION_UP`.
     * @param repeatCount `KeyEvent.getRepeatCount()`.
     * @param isCanceled `KeyEvent.isCanceled()`.
     * @param eventTimeMs `KeyEvent.getEventTime()` (in `SystemClock.uptimeMillis()` base).
     * @param source Whether the event arrived from foreground (`MainActivity`) or global (`AccessibilityService`).
     * @param mapping The active [ButtonMapping] for this key and scope, or `null` if not mapped/enabled.
     * @return `true` if the event was consumed by the mapper, `false` if it should be passed to the system/app.
     */
    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
        isCanceled: Boolean = false,
        eventTimeMs: Long,
        source: ButtonEventSource,
        mapping: ButtonMapping?
    ): Boolean {
        if (!RemoteButtonCatalog.isMappableKey(keyCode)) return false
        if (mapping == null || !mapping.enabled) return false
        if (!mapping.isHomeButton() && !mapping.hasAnyAssignedAction()) return false

        val scopeAllowed = when (source) {
            ButtonEventSource.FOREGROUND -> mapping.scope.allowsForeground()
            ButtonEventSource.GLOBAL -> mapping.scope.allowsGlobal()
            ButtonEventSource.INTENT -> true
        }
        if (!scopeAllowed) return false

        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return handleHomeKeyEvent(
                action = action,
                repeatCount = repeatCount,
                isCanceled = isCanceled,
                eventTimeMs = eventTimeMs,
                source = source,
                mapping = mapping
            )
        }

        return handleStandardKeyEvent(
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
     * Convenience overload accepting an Android [KeyEvent].
     */
    fun onKeyEvent(
        event: KeyEvent,
        source: ButtonEventSource,
        mapping: ButtonMapping?
    ): Boolean = onKeyEvent(
        keyCode = event.keyCode,
        action = event.action,
        repeatCount = event.repeatCount,
        isCanceled = event.isCanceled,
        eventTimeMs = event.eventTime,
        source = source,
        mapping = mapping
    )

    /**
     * Resolves a Home press delivered via `HomeForegroundDelivery` or `LauncherHomeRequest`
     * (intent or accessibility release callback).
     *
     * @param pressedAtMs Uptime timestamp of the Home press.
     * @param mapping Active Home [ButtonMapping], or `null` if Home mapping is disabled.
     * @param forceDoublePress If `true` (e.g. `EXTRA_OPEN_PROFILE_SELECTION`), forces double-press resolution.
     * @param source Event source (`FOREGROUND`, `GLOBAL`, or `INTENT`).
     * @return The [HomeKeyPress] classification (`SINGLE`, `DOUBLE`, or `IGNORED`).
     */
    fun onHomePress(
        pressedAtMs: Long,
        mapping: ButtonMapping?,
        forceDoublePress: Boolean = false,
        source: ButtonEventSource = ButtonEventSource.INTENT
    ): HomeKeyPress {
        if (mapping == null || !mapping.enabled) {
            return HomeKeyPress.IGNORED
        }

        var actionToEmit: ResolvedButtonGesture? = null
        val press: HomeKeyPress = synchronized(lock) {
            val state = keyStates.getOrPut(KeyEvent.KEYCODE_HOME) { KeyState() }
            state.cancelLongPress()
            if (state.longPressFired &&
                state.downTimeMs != Long.MIN_VALUE &&
                pressedAtMs - state.downTimeMs in 0L..MAX_STALE_DOWN_MS
            ) {
                state.isDown = false
                state.longPressFired = false
                return@synchronized HomeKeyPress.IGNORED
            }
            state.isDown = false
            state.longPressFired = false

            val classified = homeKeyGesture.onPress(pressedAtMs)
            if (classified == HomeKeyPress.IGNORED) {
                return@synchronized HomeKeyPress.IGNORED
            }

            val effectivePress = if (forceDoublePress) HomeKeyPress.DOUBLE else classified
            val pressType = when (effectivePress) {
                HomeKeyPress.SINGLE -> ButtonPressType.SINGLE_PRESS
                HomeKeyPress.DOUBLE -> ButtonPressType.DOUBLE_PRESS
                HomeKeyPress.IGNORED -> return@synchronized HomeKeyPress.IGNORED
            }
            val configuredAction = mapping.actionFor(pressType)
            if (configuredAction != ButtonAction.None) {
                actionToEmit = ResolvedButtonGesture(
                    keyCode = KeyEvent.KEYCODE_HOME,
                    pressType = pressType,
                    action = configuredAction,
                    source = source,
                    timestampMs = pressedAtMs
                )
            }
            effectivePress
        }

        actionToEmit?.let(onActionResolved)
        return press
    }

    /**
     * Primes the underlying [HomeKeyGesture] on a cold `CATEGORY_HOME` launch so a second Home press
     * within the double-tap window is still recognized as a double-press.
     */
    fun primeFirstHomePress(nowMs: Long) {
        synchronized(lock) {
            homeKeyGesture.onPress(nowMs)
        }
    }

    /**
     * Exposes the shared [HomeKeyGesture] instance for [LauncherHomeRequest] compatibility.
     */
    fun sharedHomeKeyGesture(): HomeKeyGesture = homeKeyGesture

    /**
     * Cancels all pending gestures across all buttons (e.g., on Activity pause, focus loss, or capture start).
     */
    fun cancelAll() {
        synchronized(lock) {
            keyStates.values.forEach { it.resetAll() }
            keyStates.clear()
        }
    }

    /**
     * Cancels any pending gesture for a single [keyCode].
     */
    fun cancelKey(keyCode: Int) {
        synchronized(lock) {
            keyStates.remove(keyCode)?.resetAll()
        }
    }

    private fun handleHomeKeyEvent(
        action: Int,
        repeatCount: Int,
        isCanceled: Boolean,
        eventTimeMs: Long,
        source: ButtonEventSource,
        mapping: ButtonMapping
    ): Boolean {
        var resolvedGesture: ResolvedButtonGesture? = null
        val consumed = synchronized(lock) {
            val state = keyStates.getOrPut(KeyEvent.KEYCODE_HOME) { KeyState() }
            val config = timingConfig

            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (repeatCount > 0) {
                        return@synchronized true
                    }
                    if (isDuplicateEvent(
                            lastTimeMs = state.lastHandledDownTimeMs,
                            lastSource = state.lastHandledDownSource,
                            eventTimeMs = eventTimeMs,
                            source = source,
                            minGapMs = config.minDuplicateGapMs
                        )
                    ) {
                        return@synchronized true
                    }
                    state.lastHandledDownTimeMs = eventTimeMs
                    state.lastHandledDownSource = source
                    state.cancelLongPress()
                    state.isDown = true
                    state.downTimeMs = eventTimeMs
                    state.downSource = source
                    state.longPressFired = false

                    val longAction = mapping.longAction
                    if (longAction != ButtonAction.None) {
                        val downStamp = eventTimeMs
                        state.longPressTask = scheduler.schedule(config.longPressThresholdMs) {
                            var longGesture: ResolvedButtonGesture? = null
                            synchronized(lock) {
                                val current = keyStates[KeyEvent.KEYCODE_HOME]
                                if (current != null &&
                                    current.isDown &&
                                    !current.longPressFired &&
                                    current.downTimeMs == downStamp
                                ) {
                                    current.longPressFired = true
                                    current.longPressTask = null
                                    longGesture = ResolvedButtonGesture(
                                        keyCode = KeyEvent.KEYCODE_HOME,
                                        pressType = ButtonPressType.LONG_PRESS,
                                        action = longAction,
                                        source = source,
                                        timestampMs = downStamp + config.longPressThresholdMs
                                    )
                                }
                            }
                            longGesture?.let(onActionResolved)
                        }
                    }
                    true
                }

                KeyEvent.ACTION_UP -> {
                    state.cancelLongPress()
                    if (isCanceled) {
                        state.isDown = false
                        state.longPressFired = false
                        return@synchronized true
                    }
                    if (isDuplicateEvent(
                            lastTimeMs = state.lastHandledUpTimeMs,
                            lastSource = state.lastHandledUpSource,
                            eventTimeMs = eventTimeMs,
                            source = source,
                            minGapMs = config.minDuplicateGapMs
                        )
                    ) {
                        return@synchronized true
                    }
                    state.lastHandledUpTimeMs = eventTimeMs
                    state.lastHandledUpSource = source

                    val wasDown = state.isDown &&
                        state.downTimeMs != Long.MIN_VALUE &&
                        (eventTimeMs - state.downTimeMs) in 0L..MAX_STALE_DOWN_MS
                    val heldDurationMs = if (wasDown) eventTimeMs - state.downTimeMs else 0L
                    val alreadyFiredLong = state.longPressFired

                    state.isDown = false
                    state.longPressFired = false

                    if (alreadyFiredLong) {
                        return@synchronized true
                    }

                    // If held past the long-press threshold and long-press is assigned, fire it now
                    // even if the scheduled callback had not yet executed.
                    if (wasDown && heldDurationMs >= config.longPressThresholdMs) {
                        if (mapping.longAction != ButtonAction.None) {
                            resolvedGesture = ResolvedButtonGesture(
                                keyCode = KeyEvent.KEYCODE_HOME,
                                pressType = ButtonPressType.LONG_PRESS,
                                action = mapping.longAction,
                                source = source,
                                timestampMs = eventTimeMs
                            )
                        }
                        return@synchronized true
                    }

                    val classified = homeKeyGesture.onPress(eventTimeMs)
                    val pressType = when (classified) {
                        HomeKeyPress.SINGLE -> ButtonPressType.SINGLE_PRESS
                        HomeKeyPress.DOUBLE -> ButtonPressType.DOUBLE_PRESS
                        HomeKeyPress.IGNORED -> null
                    }
                    if (pressType != null) {
                        val targetAction = mapping.actionFor(pressType)
                        if (targetAction != ButtonAction.None) {
                            resolvedGesture = ResolvedButtonGesture(
                                keyCode = KeyEvent.KEYCODE_HOME,
                                pressType = pressType,
                                action = targetAction,
                                source = source,
                                timestampMs = eventTimeMs
                            )
                        }
                    }
                    true
                }

                else -> false
            }
        }

        resolvedGesture?.let(onActionResolved)
        return consumed
    }

    private fun handleStandardKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isCanceled: Boolean,
        eventTimeMs: Long,
        source: ButtonEventSource,
        mapping: ButtonMapping
    ): Boolean {
        var immediateGesture: ResolvedButtonGesture? = null
        val consumed = synchronized(lock) {
            val state = keyStates.getOrPut(keyCode) { KeyState() }
            val config = timingConfig

            // Expire any stale down state caused by a missing ACTION_UP earlier.
            if (state.isDown &&
                state.downTimeMs != Long.MIN_VALUE &&
                (eventTimeMs - state.downTimeMs) > MAX_STALE_DOWN_MS
            ) {
                state.cancelLongPress()
                state.isDown = false
                state.longPressFired = false
            }

            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (repeatCount > 0) {
                        // Suppress key-repeat events while a mapped button is held.
                        return@synchronized true
                    }
                    if (isDuplicateEvent(
                            lastTimeMs = state.lastHandledDownTimeMs,
                            lastSource = state.lastHandledDownSource,
                            eventTimeMs = eventTimeMs,
                            source = source,
                            minGapMs = config.minDuplicateGapMs
                        )
                    ) {
                        return@synchronized true
                    }

                    state.lastHandledDownTimeMs = eventTimeMs
                    state.lastHandledDownSource = source
                    state.cancelLongPress()
                    state.isDown = true
                    state.downTimeMs = eventTimeMs
                    state.downSource = source
                    state.longPressFired = false

                    // If a first tap was waiting for the double-click window and the second tap
                    // has now started within the window, cancel the pending single-tap timer so
                    // it cannot fire mid-press.
                    if (state.pendingTapCount == 1 &&
                        state.firstReleaseTimeMs != Long.MIN_VALUE &&
                        (eventTimeMs - state.firstReleaseTimeMs) <= config.doubleClickWindowMs
                    ) {
                        state.singleTapTask?.cancel()
                        state.singleTapTask = null
                    } else if (state.pendingTapCount > 0) {
                        state.cancelSingleTap()
                    }

                    val longAction = mapping.longAction
                    if (longAction != ButtonAction.None && state.pendingTapCount == 0) {
                        val downStamp = eventTimeMs
                        state.longPressTask = scheduler.schedule(config.longPressThresholdMs) {
                            var longGesture: ResolvedButtonGesture? = null
                            synchronized(lock) {
                                val current = keyStates[keyCode]
                                if (current != null &&
                                    current.isDown &&
                                    !current.longPressFired &&
                                    current.downTimeMs == downStamp
                                ) {
                                    current.longPressFired = true
                                    current.longPressTask = null
                                    current.cancelSingleTap()
                                    longGesture = ResolvedButtonGesture(
                                        keyCode = keyCode,
                                        pressType = ButtonPressType.LONG_PRESS,
                                        action = longAction,
                                        source = source,
                                        timestampMs = downStamp + config.longPressThresholdMs
                                    )
                                }
                            }
                            longGesture?.let(onActionResolved)
                        }
                    }
                    true
                }

                KeyEvent.ACTION_UP -> {
                    state.cancelLongPress()
                    if (isCanceled) {
                        state.resetAll()
                        return@synchronized true
                    }

                    // Prevent duplicate execution from foreground + global handlers or bounce.
                    if (isDuplicateUpEvent(
                            state = state,
                            eventTimeMs = eventTimeMs,
                            source = source,
                            minGapMs = config.minDuplicateGapMs
                        )
                    ) {
                        return@synchronized true
                    }

                    val wasDown = state.isDown &&
                        state.downTimeMs != Long.MIN_VALUE &&
                        (eventTimeMs - state.downTimeMs) in 0L..MAX_STALE_DOWN_MS
                    val heldDurationMs = if (wasDown) eventTimeMs - state.downTimeMs else 0L
                    val alreadyFiredLong = state.longPressFired

                    state.lastHandledUpTimeMs = eventTimeMs
                    state.lastHandledUpSource = source
                    state.isDown = false
                    state.longPressFired = false

                    if (alreadyFiredLong) {
                        state.cancelSingleTap()
                        return@synchronized true
                    }

                    // If held for at least the long-press threshold, treat as a long press.
                    if (wasDown && heldDurationMs >= config.longPressThresholdMs) {
                        state.cancelSingleTap()
                        if (mapping.longAction != ButtonAction.None) {
                            immediateGesture = ResolvedButtonGesture(
                                keyCode = keyCode,
                                pressType = ButtonPressType.LONG_PRESS,
                                action = mapping.longAction,
                                source = source,
                                timestampMs = eventTimeMs
                            )
                        }
                        return@synchronized true
                    }

                    // Check if this release completes a double click within the window.
                    val isSecondTapInWindow = state.pendingTapCount == 1 &&
                        state.firstReleaseTimeMs != Long.MIN_VALUE &&
                        (eventTimeMs - state.firstReleaseTimeMs) in 0L..config.doubleClickWindowMs

                    if (isSecondTapInWindow) {
                        state.cancelSingleTap()
                        if (mapping.doubleAction != ButtonAction.None) {
                            immediateGesture = ResolvedButtonGesture(
                                keyCode = keyCode,
                                pressType = ButtonPressType.DOUBLE_PRESS,
                                action = mapping.doubleAction,
                                source = source,
                                timestampMs = eventTimeMs
                            )
                        }
                        return@synchronized true
                    }

                    // First tap: wait for the double-click window to expire before firing singleAction.
                    state.cancelSingleTap()
                    state.pendingTapCount = 1
                    state.firstReleaseTimeMs = eventTimeMs
                    val releaseStamp = eventTimeMs
                    val singleAction = mapping.singleAction
                    state.singleTapTask = scheduler.schedule(config.doubleClickWindowMs) {
                        var singleGesture: ResolvedButtonGesture? = null
                        synchronized(lock) {
                            val current = keyStates[keyCode]
                            if (current != null &&
                                current.pendingTapCount == 1 &&
                                current.firstReleaseTimeMs == releaseStamp &&
                                !current.isDown
                            ) {
                                current.pendingTapCount = 0
                                current.firstReleaseTimeMs = Long.MIN_VALUE
                                current.singleTapTask = null
                                if (singleAction != ButtonAction.None) {
                                    singleGesture = ResolvedButtonGesture(
                                        keyCode = keyCode,
                                        pressType = ButtonPressType.SINGLE_PRESS,
                                        action = singleAction,
                                        source = source,
                                        timestampMs = releaseStamp + config.doubleClickWindowMs
                                    )
                                }
                            }
                        }
                        singleGesture?.let(onActionResolved)
                    }
                    true
                }

                else -> false
            }
        }

        immediateGesture?.let(onActionResolved)
        return consumed
    }

    private fun isDuplicateEvent(
        lastTimeMs: Long,
        lastSource: ButtonEventSource?,
        eventTimeMs: Long,
        source: ButtonEventSource,
        minGapMs: Long
    ): Boolean {
        if (lastTimeMs == Long.MIN_VALUE) return false
        val delta = eventTimeMs - lastTimeMs
        if (delta < 0L) return true
        // Cross-source events (e.g. AccessibilityService + Activity) or rapid hardware bounce
        // within minGapMs are suppressed.
        if (lastSource != null && lastSource != source && delta <= minGapMs) {
            return true
        }
        return delta < minGapMs && lastSource == source
    }

    private fun isDuplicateUpEvent(
        state: KeyState,
        eventTimeMs: Long,
        source: ButtonEventSource,
        minGapMs: Long
    ): Boolean {
        if (state.lastHandledUpTimeMs == Long.MIN_VALUE) return false
        val delta = eventTimeMs - state.lastHandledUpTimeMs
        if (delta < 0L) return true
        // Cross-source duplicate release within minGapMs
        if (state.lastHandledUpSource != null && state.lastHandledUpSource != source && delta <= minGapMs) {
            return true
        }
        // Same-source release within minGapMs without a new ACTION_DOWN in between
        if (delta < minGapMs && (!state.isDown || state.downTimeMs <= state.lastHandledUpTimeMs)) {
            return true
        }
        return false
    }

    companion object {
        private const val MAX_STALE_DOWN_MS = 5_000L
    }
}
