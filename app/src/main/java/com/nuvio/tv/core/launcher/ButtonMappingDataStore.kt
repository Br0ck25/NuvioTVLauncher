package com.nuvio.tv.core.launcher

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.buttonMappingPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "button_mapper_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Persisted state snapshot for the Button Mapper feature.
 */
data class ButtonMapperPersistedState(
    val schemaVersion: Int = ButtonMappingDataStore.CURRENT_SCHEMA_VERSION,
    val initialized: Boolean = true,
    val masterEnabled: Boolean = true,
    val homeCustomizationEnabled: Boolean = true,
    val mappings: List<ButtonMapping> = listOf(ButtonMapping.defaultHomeMapping()),
    val observedForegroundKeyCodes: Set<Int> = emptySet(),
    val observedGlobalKeyCodes: Set<Int> = emptySet(),
    val timingConfig: ButtonMapperTimingConfig = ButtonMapperTimingConfig()
) {
    fun mappingFor(keyCode: Int): ButtonMapping? =
        mappings.firstOrNull { it.keyCode == keyCode }

    fun homeMapping(): ButtonMapping =
        mappingFor(KeyEvent.KEYCODE_HOME) ?: ButtonMapping.defaultHomeMapping()
}

private data class SerializedButtonMapping(
    val keyCode: Int = 0,
    val displayName: String? = null,
    val singleAction: String? = null,
    val doubleAction: String? = null,
    val longAction: String? = null,
    val enabled: Boolean? = true,
    val scope: String? = null
)

/**
 * Persistence layer for Button Mapper configurations, Home defaults, and observed key capabilities.
 *
 * Ensures that on first install or migration from an older version without Button Mapper state,
 * the factory Home button mapping (Single = Go to Top of Home, Double = Open Profile Switcher,
 * Long = Unassigned) is seeded automatically without overwriting existing user configurations.
 */
@Singleton
class ButtonMappingDataStore(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.buttonMappingPreferencesDataStore)

    private val gson = Gson()
    private val writeMutex = Mutex()

    private val schemaVersionKey = intPreferencesKey("schema_version")
    private val initializedKey = booleanPreferencesKey("initialized")
    private val masterEnabledKey = booleanPreferencesKey("master_enabled")
    private val homeCustomizationEnabledKey = booleanPreferencesKey("home_customization_enabled")
    private val mappingsJsonKey = stringPreferencesKey("mappings_json")
    private val observedForegroundJsonKey = stringPreferencesKey("observed_foreground_key_codes_json")
    private val observedGlobalJsonKey = stringPreferencesKey("observed_global_key_codes_json")
    private val doubleClickWindowMsKey = longPreferencesKey("double_click_window_ms")
    private val longPressThresholdMsKey = longPreferencesKey("long_press_threshold_ms")

    val state: Flow<ButtonMapperPersistedState> = dataStore.data.map(::decodeState)

    suspend fun currentState(): ButtonMapperPersistedState = state.first()

    /**
     * Ensures the default Home mapping is persisted on first launch or migration while
     * leaving any existing user mappings untouched.
     */
    suspend fun ensureInitialized(): ButtonMapperPersistedState = mutate { current ->
        current.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            initialized = true
        )
    }

    suspend fun mutate(
        transform: (ButtonMapperPersistedState) -> ButtonMapperPersistedState
    ): ButtonMapperPersistedState = writeMutex.withLock {
        var updatedState = ButtonMapperPersistedState()
        dataStore.edit { prefs ->
            val current = decodeState(prefs)
            val next = sanitizeState(transform(current))
            updatedState = next
            encodeState(prefs, next)
        }
        updatedState
    }

    private fun decodeState(prefs: Preferences): ButtonMapperPersistedState {
        val isInitialized = prefs[initializedKey] ?: false
        val rawMappingsJson = prefs[mappingsJsonKey]
        val parsedMappings = parseMappings(rawMappingsJson)

        // First-install / migration rule: if not yet initialized and no mappings were stored,
        // seed the default Home button mapping. If mappings already exist, preserve them.
        val effectiveMappings = if (!isInitialized && rawMappingsJson.isNullOrBlank()) {
            listOf(ButtonMapping.defaultHomeMapping())
        } else {
            ensureHomeMappingPresentIfInitialized(parsedMappings, rawMappingsJson)
        }

        val doubleClickWindowMs = (prefs[doubleClickWindowMsKey]
            ?: ButtonMapperTimingConfig.DEFAULT_DOUBLE_CLICK_WINDOW_MS).coerceIn(200L, 1_000L)
        val longPressThresholdMs = (prefs[longPressThresholdMsKey]
            ?: ButtonMapperTimingConfig.DEFAULT_LONG_PRESS_THRESHOLD_MS).coerceIn(300L, 2_000L)

        return ButtonMapperPersistedState(
            schemaVersion = prefs[schemaVersionKey] ?: CURRENT_SCHEMA_VERSION,
            initialized = true,
            masterEnabled = prefs[masterEnabledKey] ?: true,
            homeCustomizationEnabled = prefs[homeCustomizationEnabledKey] ?: true,
            mappings = effectiveMappings,
            observedForegroundKeyCodes = parseIntSet(prefs[observedForegroundJsonKey]),
            observedGlobalKeyCodes = parseIntSet(prefs[observedGlobalJsonKey]),
            timingConfig = ButtonMapperTimingConfig(
                doubleClickWindowMs = doubleClickWindowMs,
                longPressThresholdMs = longPressThresholdMs
            )
        )
    }

    private fun ensureHomeMappingPresentIfInitialized(
        parsedMappings: List<ButtonMapping>,
        rawMappingsJson: String?
    ): List<ButtonMapping> {
        if (rawMappingsJson == null) {
            return listOf(ButtonMapping.defaultHomeMapping())
        }
        return parsedMappings
    }

    private fun sanitizeState(state: ButtonMapperPersistedState): ButtonMapperPersistedState {
        val deduplicated = LinkedHashMap<Int, ButtonMapping>()
        for (mapping in state.mappings) {
            if (!RemoteButtonCatalog.isMappableKey(mapping.keyCode)) continue
            val cleanName = mapping.displayName.trim().ifBlank {
                RemoteButtonCatalog.labelForKeyCode(mapping.keyCode)
            }
            deduplicated[mapping.keyCode] = mapping.copy(displayName = cleanName)
        }
        // Keep Home first in the list when present for consistent UI ordering.
        val sortedMappings = deduplicated.values.sortedWith(
            compareBy<ButtonMapping> { it.keyCode != KeyEvent.KEYCODE_HOME }
                .thenBy { it.displayName.lowercase() }
        )
        return state.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            initialized = true,
            mappings = sortedMappings,
            observedForegroundKeyCodes = state.observedForegroundKeyCodes.filterTo(linkedSetOf()) { it > 0 },
            observedGlobalKeyCodes = state.observedGlobalKeyCodes.filterTo(linkedSetOf()) { it > 0 }
        )
    }

    private fun encodeState(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        state: ButtonMapperPersistedState
    ) {
        prefs[schemaVersionKey] = state.schemaVersion
        prefs[initializedKey] = true
        prefs[masterEnabledKey] = state.masterEnabled
        prefs[homeCustomizationEnabledKey] = state.homeCustomizationEnabled
        val serialized = state.mappings.map { mapping ->
            SerializedButtonMapping(
                keyCode = mapping.keyCode,
                displayName = mapping.displayName,
                singleAction = mapping.singleAction.storageKey,
                doubleAction = mapping.doubleAction.storageKey,
                longAction = mapping.longAction.storageKey,
                enabled = mapping.enabled,
                scope = mapping.scope.name
            )
        }
        prefs[mappingsJsonKey] = gson.toJson(serialized)
        prefs[observedForegroundJsonKey] = gson.toJson(state.observedForegroundKeyCodes.sorted())
        prefs[observedGlobalJsonKey] = gson.toJson(state.observedGlobalKeyCodes.sorted())
        prefs[doubleClickWindowMsKey] = state.timingConfig.doubleClickWindowMs
        prefs[longPressThresholdMsKey] = state.timingConfig.longPressThresholdMs
    }

    private fun parseMappings(raw: String?): List<ButtonMapping> {
        if (raw.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<SerializedButtonMapping>>() {}.type
        val parsed = runCatching { gson.fromJson<List<SerializedButtonMapping>>(raw, type) }
            .getOrNull()
            .orEmpty()
        val result = LinkedHashMap<Int, ButtonMapping>()
        for (item in parsed) {
            if (!RemoteButtonCatalog.isMappableKey(item.keyCode)) continue
            val scope = runCatching {
                item.scope?.let(ButtonMappingScope::valueOf)
            }.getOrNull() ?: ButtonMappingScope.FOREGROUND_AND_GLOBAL
            val displayName = item.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: RemoteButtonCatalog.labelForKeyCode(item.keyCode)
            result[item.keyCode] = ButtonMapping(
                keyCode = item.keyCode,
                displayName = displayName,
                singleAction = ButtonAction.fromStorageKey(item.singleAction),
                doubleAction = ButtonAction.fromStorageKey(item.doubleAction),
                longAction = ButtonAction.fromStorageKey(item.longAction),
                enabled = item.enabled ?: true,
                scope = scope
            )
        }
        return result.values.toList()
    }

    private fun parseIntSet(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val type = object : TypeToken<List<Int>>() {}.type
        return runCatching { gson.fromJson<List<Int>>(raw, type) }
            .getOrNull()
            .orEmpty()
            .filterTo(linkedSetOf()) { it > 0 }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
