package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Result of a captured physical remote button press.
 */
data class CapturedButtonInfo(
    val keyCode: Int,
    val keyCodeLabel: String,
    val descriptor: RemoteButtonDescriptor,
    val availableInNuvio: Boolean,
    val availableGlobally: Boolean,
    val isSystemReserved: Boolean,
    val capabilityStatus: ButtonCapabilityStatus,
    val capturedFromSource: ButtonEventSource
)

/**
 * Lifecycle state of a temporary button capture session.
 */
sealed interface ButtonCaptureState {
    data object Idle : ButtonCaptureState
    data class Listening(
        val remainingSeconds: Int,
        val totalSeconds: Int
    ) : ButtonCaptureState
    data class Captured(val info: CapturedButtonInfo) : ButtonCaptureState
    data object TimedOut : ButtonCaptureState
}

/**
 * Coordinates temporary remote button key capture for the "Add Button Mapping" dialog.
 *
 * When a capture session is in [ButtonCaptureState.Listening], both foreground (`MainActivity`)
 * and global (`LauncherAccessibilityService`) key handlers offer incoming key events here first.
 * D-pad, Enter, and Back remain available to navigate or cancel the dialog UI, while any other
 * physical remote button press is captured and inspected without persisting until user confirmation.
 */
@Singleton
class ButtonCaptureController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _state = MutableStateFlow<ButtonCaptureState>(ButtonCaptureState.Idle)
    val state: StateFlow<ButtonCaptureState> = _state.asStateFlow()

    private var timeoutJob: Job? = null
    @Volatile
    private var pendingReleaseKeyCode: Int = -1

    init {
        activeInstance.set(this)
    }

    fun isListening(): Boolean = _state.value is ButtonCaptureState.Listening

    fun isCapturingOrReleasing(keyCode: Int, action: Int): Boolean {
        if (_state.value is ButtonCaptureState.Listening) return true
        return pendingReleaseKeyCode != -1 &&
            pendingReleaseKeyCode == keyCode &&
            (action == KeyEvent.ACTION_UP || action == KeyEvent.ACTION_DOWN)
    }

    /**
     * Starts listening for a physical remote button press with a countdown timeout.
     */
    fun startCapture(timeoutMs: Long = ButtonMapperTimingConfig.DEFAULT_CAPTURE_TIMEOUT_MS) {
        timeoutJob?.cancel()
        pendingReleaseKeyCode = -1
        val totalSeconds = (timeoutMs / 1_000L).toInt().coerceAtLeast(1)
        _state.value = ButtonCaptureState.Listening(
            remainingSeconds = totalSeconds,
            totalSeconds = totalSeconds
        )
        timeoutJob = scope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && _state.value is ButtonCaptureState.Listening) {
                delay(1_000L)
                remaining -= 1
                if (_state.value is ButtonCaptureState.Listening && remaining > 0) {
                    _state.value = ButtonCaptureState.Listening(
                        remainingSeconds = remaining,
                        totalSeconds = totalSeconds
                    )
                }
            }
            if (_state.value is ButtonCaptureState.Listening) {
                _state.value = ButtonCaptureState.TimedOut
            }
        }
    }

    /**
     * Cancels any active capture session and resets to [ButtonCaptureState.Idle].
     */
    fun cancelCapture() {
        timeoutJob?.cancel()
        timeoutJob = null
        pendingReleaseKeyCode = -1
        _state.value = ButtonCaptureState.Idle
    }

    /**
     * Selects a button manually (e.g. from the preset list) into the [ButtonCaptureState.Captured] state
     * so the user can configure its single, double, and long actions before confirming.
     */
    fun selectButtonForDraft(
        keyCode: Int,
        repository: ButtonMappingRepository?,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean = false
    ) {
        timeoutJob?.cancel()
        timeoutJob = null
        pendingReleaseKeyCode = -1
        _state.value = ButtonCaptureState.Captured(
            buildCapturedInfo(
                keyCode = keyCode,
                source = ButtonEventSource.FOREGROUND,
                repository = repository,
                accessibilityEnabled = accessibilityEnabled,
                nativeHomeActive = nativeHomeActive,
                wasPhysicallyPressed = false
            )
        )
    }

    /**
     * Offers a physical key event during an active capture session.
     *
     * @return `true` if the capture session consumed the key event, `false` otherwise.
     */
    fun onKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        source: ButtonEventSource,
        repository: ButtonMappingRepository?,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean = false
    ): Boolean {
        if (_state.value !is ButtonCaptureState.Listening) {
            if (pendingReleaseKeyCode != -1 && pendingReleaseKeyCode == keyCode) {
                if (action == KeyEvent.ACTION_UP) {
                    pendingReleaseKeyCode = -1
                }
                return true
            }
            return false
        }
        if (keyCode <= 0) return false

        // Allow D-pad, Enter, and Back to navigate and cancel the capture dialog itself.
        if (RemoteButtonCatalog.isDpadOrEnterKey(keyCode) || keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }

        // Do not intercept actual Volume or Power at the OS level, but if a key event arrives,
        // report it as system-reserved without breaking system volume/power.
        if (RemoteButtonCatalog.isSystemReservedKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                timeoutJob?.cancel()
                timeoutJob = null
                _state.value = ButtonCaptureState.Captured(
                    buildCapturedInfo(
                        keyCode = keyCode,
                        source = source,
                        repository = repository,
                        accessibilityEnabled = accessibilityEnabled,
                        nativeHomeActive = nativeHomeActive,
                        wasPhysicallyPressed = true
                    )
                )
            }
            return false
        }

        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            repository?.recordKeyObserved(
                keyCode = keyCode,
                isGlobal = source == ButtonEventSource.GLOBAL
            )
            timeoutJob?.cancel()
            timeoutJob = null
            pendingReleaseKeyCode = keyCode
            _state.value = ButtonCaptureState.Captured(
                buildCapturedInfo(
                    keyCode = keyCode,
                    source = source,
                    repository = repository,
                    accessibilityEnabled = accessibilityEnabled,
                    nativeHomeActive = nativeHomeActive,
                    wasPhysicallyPressed = true
                )
            )
        }
        return true
    }

    private fun buildCapturedInfo(
        keyCode: Int,
        source: ButtonEventSource,
        repository: ButtonMappingRepository?,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean,
        wasPhysicallyPressed: Boolean
    ): CapturedButtonInfo {
        val descriptor = RemoteButtonCatalog.descriptorFor(keyCode)
        val rawCodeName = runCatching { KeyEvent.keyCodeToString(keyCode) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "KEYCODE_$keyCode"

        if (descriptor.isSystemReserved) {
            return CapturedButtonInfo(
                keyCode = keyCode,
                keyCodeLabel = "$rawCodeName ($keyCode)",
                descriptor = descriptor,
                availableInNuvio = false,
                availableGlobally = false,
                isSystemReserved = true,
                capabilityStatus = ButtonCapabilityStatus.SYSTEM_RESERVED,
                capturedFromSource = source
            )
        }

        val status = repository?.capabilityStatusFor(
            keyCode = keyCode,
            accessibilityEnabled = accessibilityEnabled,
            nativeHomeActive = nativeHomeActive
        ) ?: if (wasPhysicallyPressed) {
            if (source == ButtonEventSource.GLOBAL && accessibilityEnabled) {
                ButtonCapabilityStatus.AVAILABLE_GLOBALLY
            } else {
                ButtonCapabilityStatus.AVAILABLE_IN_NUVIO
            }
        } else {
            ButtonCapabilityStatus.UNKNOWN
        }

        val availableGlobally = status == ButtonCapabilityStatus.AVAILABLE_GLOBALLY ||
            (source == ButtonEventSource.GLOBAL && accessibilityEnabled)
        val availableInNuvio = availableGlobally ||
            wasPhysicallyPressed ||
            status == ButtonCapabilityStatus.AVAILABLE_IN_NUVIO ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_MENU

        val effectiveStatus = when {
            availableGlobally -> ButtonCapabilityStatus.AVAILABLE_GLOBALLY
            availableInNuvio -> ButtonCapabilityStatus.AVAILABLE_IN_NUVIO
            else -> status
        }

        return CapturedButtonInfo(
            keyCode = keyCode,
            keyCodeLabel = "$rawCodeName ($keyCode)",
            descriptor = descriptor,
            availableInNuvio = availableInNuvio,
            availableGlobally = availableGlobally,
            isSystemReserved = false,
            capabilityStatus = effectiveStatus,
            capturedFromSource = source
        )
    }

    companion object {
        private val activeInstance = AtomicReference<ButtonCaptureController?>(null)

        fun currentInstance(): ButtonCaptureController? = activeInstance.get()
    }
}
