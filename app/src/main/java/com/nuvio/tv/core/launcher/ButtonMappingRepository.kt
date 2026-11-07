package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Authoritative repository for remote button mappings, Home button customization, and
 * observed per-button capability state.
 *
 * Keeps an in-memory [StateFlow] snapshot synchronized with [ButtonMappingDataStore] so
 * foreground (`MainActivity.dispatchKeyEvent`) and global (`LauncherAccessibilityService.onKeyEvent`)
 * key handlers never perform disk or database I/O on the input thread.
 */
@Singleton
class ButtonMappingRepository(
    private val dataStore: ButtonMappingDataStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Inject
    constructor(dataStore: ButtonMappingDataStore) : this(dataStore, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(ButtonMapperPersistedState())
    val state: StateFlow<ButtonMapperPersistedState> = _state.asStateFlow()

    private val runtimeObservedForeground = ConcurrentHashMap.newKeySet<Int>()
    private val runtimeObservedGlobal = ConcurrentHashMap.newKeySet<Int>()

    init {
        activeInstance.set(this)
        scope.launch {
            runCatching { dataStore.ensureInitialized() }
            dataStore.state.collect { persisted ->
                runtimeObservedForeground.addAll(persisted.observedForegroundKeyCodes)
                runtimeObservedGlobal.addAll(persisted.observedGlobalKeyCodes)
                _state.value = persisted.copy(
                    observedForegroundKeyCodes = persisted.observedForegroundKeyCodes + runtimeObservedForeground,
                    observedGlobalKeyCodes = persisted.observedGlobalKeyCodes + runtimeObservedGlobal
                )
            }
        }
    }

    /**
     * Fast, non-blocking snapshot read for `onKeyEvent` and `dispatchKeyEvent`.
     */
    fun currentSnapshot(): ButtonMapperPersistedState = _state.value

    /**
     * Resolves the effective [ButtonMapping] for [keyCode] if enabled and permitted in the given context.
     *
     * Special rule for Home (`KEYCODE_HOME`):
     * - When `homeCustomizationEnabled` is `false`, returns the factory default Home mapping
     *   (`Single = GoToTopOfHome`, `Double = OpenProfileSwitcher`, `Long = None`) so disabling
     *   Home customization cleanly restores standard Nuvio Home behavior.
     * - When `homeCustomizationEnabled` is `true`, returns the user's configured Home mapping
     *   (or `null` if the user explicitly disabled or deleted the Home mapping).
     */
    fun activeMappingFor(keyCode: Int, isGlobal: Boolean): ButtonMapping? {
        if (!RemoteButtonCatalog.isMappableKey(keyCode)) return null
        val snapshot = _state.value

        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (!snapshot.masterEnabled || !snapshot.homeCustomizationEnabled) {
                return ButtonMapping.defaultHomeMapping()
            }
            val homeMapping = snapshot.mappingFor(KeyEvent.KEYCODE_HOME) ?: return null
            if (!homeMapping.enabled) return null
            val scopeAllowed = if (isGlobal) {
                homeMapping.scope.allowsGlobal()
            } else {
                homeMapping.scope.allowsForeground()
            }
            return homeMapping.takeIf { scopeAllowed }
        }

        if (!snapshot.masterEnabled) return null
        val mapping = snapshot.mappingFor(keyCode) ?: return null
        if (!mapping.enabled) return null
        val scopeAllowed = if (isGlobal) {
            mapping.scope.allowsGlobal()
        } else {
            mapping.scope.allowsForeground()
        }
        return if (scopeAllowed && mapping.hasAnyAssignedAction()) mapping else null
    }

    /**
     * Determines per-button capability status based on system policy and actual observed key events.
     */
    fun capabilityStatusFor(
        keyCode: Int,
        accessibilityEnabled: Boolean,
        nativeHomeActive: Boolean = false
    ): ButtonCapabilityStatus {
        if (RemoteButtonCatalog.isSystemReservedKey(keyCode)) {
            return ButtonCapabilityStatus.SYSTEM_RESERVED
        }
        val snapshot = _state.value
        val seenGlobally = keyCode in snapshot.observedGlobalKeyCodes || keyCode in runtimeObservedGlobal
        val seenForeground = keyCode in snapshot.observedForegroundKeyCodes || keyCode in runtimeObservedForeground

        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return if (accessibilityEnabled || nativeHomeActive || seenGlobally) {
                ButtonCapabilityStatus.AVAILABLE_GLOBALLY
            } else {
                ButtonCapabilityStatus.AVAILABLE_IN_NUVIO
            }
        }

        if (seenGlobally && accessibilityEnabled) {
            return ButtonCapabilityStatus.AVAILABLE_GLOBALLY
        }
        if (seenForeground || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            return ButtonCapabilityStatus.AVAILABLE_IN_NUVIO
        }
        return ButtonCapabilityStatus.UNKNOWN
    }

    /**
     * Records that a physical button key event was observed in Nuvio or globally.
     * Safe to call on the main/input thread: updates in-memory state immediately and
     * persists asynchronously only when a key is newly discovered.
     */
    fun recordKeyObserved(keyCode: Int, isGlobal: Boolean) {
        if (keyCode <= 0 || RemoteButtonCatalog.isDpadOrEnterKey(keyCode)) return
        val targetSet = if (isGlobal) runtimeObservedGlobal else runtimeObservedForeground
        val added = targetSet.add(keyCode)
        // Always mark foreground availability when a key is observed globally as well.
        if (isGlobal) {
            runtimeObservedForeground.add(keyCode)
        }
        if (!added) return

        val current = _state.value
        _state.value = current.copy(
            observedForegroundKeyCodes = current.observedForegroundKeyCodes + runtimeObservedForeground,
            observedGlobalKeyCodes = current.observedGlobalKeyCodes + runtimeObservedGlobal
        )
        scope.launch {
            runCatching {
                dataStore.mutate { persisted ->
                    persisted.copy(
                        observedForegroundKeyCodes = persisted.observedForegroundKeyCodes + runtimeObservedForeground,
                        observedGlobalKeyCodes = persisted.observedGlobalKeyCodes + runtimeObservedGlobal
                    )
                }
            }
        }
    }

    suspend fun setMasterEnabled(enabled: Boolean): ButtonMapperPersistedState = updateState { current ->
        current.copy(masterEnabled = enabled)
    }

    suspend fun setHomeCustomizationEnabled(enabled: Boolean): ButtonMapperPersistedState = updateState { current ->
        current.copy(homeCustomizationEnabled = enabled)
    }

    suspend fun upsertMapping(mapping: ButtonMapping): ButtonMapperPersistedState = updateState { current ->
        if (!RemoteButtonCatalog.isMappableKey(mapping.keyCode)) return@updateState current
        val normalized = normalizeRestoreDefaultActions(mapping)
        val updated = current.mappings.filterNot { it.keyCode == normalized.keyCode } + normalized
        current.copy(mappings = updated)
    }

    suspend fun updatePressAction(
        keyCode: Int,
        pressType: ButtonPressType,
        action: ButtonAction,
        displayName: String? = null
    ): ButtonMapperPersistedState = updateState { current ->
        if (!RemoteButtonCatalog.isMappableKey(keyCode)) return@updateState current
        val resolvedAction = if (action == ButtonAction.RestoreDefault) {
            ButtonMapping.defaultActionFor(keyCode, pressType)
        } else {
            action
        }
        val existing = current.mappingFor(keyCode) ?: if (keyCode == KeyEvent.KEYCODE_HOME) {
            ButtonMapping.defaultHomeMapping()
        } else {
            ButtonMapping(
                keyCode = keyCode,
                displayName = displayName ?: RemoteButtonCatalog.labelForKeyCode(keyCode)
            )
        }
        val modified = existing
            .withAction(pressType, resolvedAction)
            .let { if (!displayName.isNullOrBlank()) it.copy(displayName = displayName) else it }
        val updated = current.mappings.filterNot { it.keyCode == keyCode } + modified
        current.copy(mappings = updated)
    }

    suspend fun clearPressAction(
        keyCode: Int,
        pressType: ButtonPressType
    ): ButtonMapperPersistedState = updatePressAction(
        keyCode = keyCode,
        pressType = pressType,
        action = ButtonAction.None
    )

    suspend fun setMappingEnabled(
        keyCode: Int,
        enabled: Boolean
    ): ButtonMapperPersistedState = updateState { current ->
        val existing = current.mappingFor(keyCode) ?: if (keyCode == KeyEvent.KEYCODE_HOME) {
            ButtonMapping.defaultHomeMapping()
        } else {
            return@updateState current
        }
        val updated = current.mappings.filterNot { it.keyCode == keyCode } + existing.copy(enabled = enabled)
        current.copy(mappings = updated)
    }

    suspend fun setMappingScope(
        keyCode: Int,
        scope: ButtonMappingScope
    ): ButtonMapperPersistedState = updateState { current ->
        val existing = current.mappingFor(keyCode) ?: return@updateState current
        val updated = current.mappings.filterNot { it.keyCode == keyCode } + existing.copy(scope = scope)
        current.copy(mappings = updated)
    }

    suspend fun deleteMapping(keyCode: Int): ButtonMapperPersistedState = updateState { current ->
        current.copy(mappings = current.mappings.filterNot { it.keyCode == keyCode })
    }

    suspend fun restoreButtonDefaults(keyCode: Int): ButtonMapperPersistedState = updateState { current ->
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            val updated = current.mappings.filterNot { it.keyCode == KeyEvent.KEYCODE_HOME } +
                ButtonMapping.defaultHomeMapping()
            current.copy(
                homeCustomizationEnabled = true,
                mappings = updated
            )
        } else {
            val existing = current.mappingFor(keyCode) ?: return@updateState current
            val resetMapping = existing.copy(
                singleAction = ButtonAction.None,
                doubleAction = ButtonAction.None,
                longAction = ButtonAction.None,
                enabled = true,
                scope = ButtonMappingScope.FOREGROUND_AND_GLOBAL
            )
            val updated = current.mappings.filterNot { it.keyCode == keyCode } + resetMapping
            current.copy(mappings = updated)
        }
    }

    suspend fun restoreHomeDefaults(): ButtonMapperPersistedState =
        restoreButtonDefaults(KeyEvent.KEYCODE_HOME)

    suspend fun resetAllToDefaults(): ButtonMapperPersistedState = updateState { current ->
        current.copy(
            masterEnabled = true,
            homeCustomizationEnabled = true,
            mappings = listOf(ButtonMapping.defaultHomeMapping()),
            timingConfig = ButtonMapperTimingConfig()
        )
    }

    private suspend fun updateState(
        transform: (ButtonMapperPersistedState) -> ButtonMapperPersistedState
    ): ButtonMapperPersistedState {
        val next = dataStore.mutate(transform)
        _state.value = next.copy(
            observedForegroundKeyCodes = next.observedForegroundKeyCodes + runtimeObservedForeground,
            observedGlobalKeyCodes = next.observedGlobalKeyCodes + runtimeObservedGlobal
        )
        return _state.value
    }

    private fun normalizeRestoreDefaultActions(mapping: ButtonMapping): ButtonMapping = mapping.copy(
        singleAction = if (mapping.singleAction == ButtonAction.RestoreDefault) {
            ButtonMapping.defaultActionFor(mapping.keyCode, ButtonPressType.SINGLE_PRESS)
        } else {
            mapping.singleAction
        },
        doubleAction = if (mapping.doubleAction == ButtonAction.RestoreDefault) {
            ButtonMapping.defaultActionFor(mapping.keyCode, ButtonPressType.DOUBLE_PRESS)
        } else {
            mapping.doubleAction
        },
        longAction = if (mapping.longAction == ButtonAction.RestoreDefault) {
            ButtonMapping.defaultActionFor(mapping.keyCode, ButtonPressType.LONG_PRESS)
        } else {
            mapping.longAction
        }
    )

    companion object {
        private val activeInstance = AtomicReference<ButtonMappingRepository?>(null)

        fun currentInstance(): ButtonMappingRepository? = activeInstance.get()
    }
}
