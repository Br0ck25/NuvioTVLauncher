package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.mylists.MyListsDefaultSeed
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "addon_preferences"
    }

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")
    private val userSetNamesKey = stringPreferencesKey("addon_user_set_names")
    private val addonEnabledStatesKey = stringPreferencesKey("installed_addon_enabled_states")
    private val manifestSuffix = "/manifest.json"

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(manifestSuffix, ignoreCase = true)) {
            path.dropLast(manifestSuffix.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    private fun ensureMyListsAddonPresent(urls: List<String>): List<String> {
        if (urls.any { MyListsDefaultSeed.isMyListsAddonUrl(it) }) {
            return urls
        }
        return listOf(MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL) + urls
    }

    val installedAddonUrls: Flow<List<String>> = combine(
        effectiveProfileIdFlow,
        profileManager.activeProfileId
    ) { sharedAddonPid, activePid ->
        sharedAddonPid to activePid
    }.flatMapLatest { (sharedAddonPid, activePid) ->
        combine(
            factory.get(sharedAddonPid, FEATURE).data,
            factory.get(activePid, "my_lists_tv_sync").data
        ) { sharedPrefs, myListsPrefs ->
            val json = sharedPrefs[orderedUrlsKey]
            val list = if (json != null) {
                parseUrlList(json)
            } else {
                val legacySet = sharedPrefs[legacyUrlsKey] ?: getDefaultAddons()
                legacySet.toList()
            }

            val targetId = myListsPrefs[stringPreferencesKey("my_lists_target_id")]?.trim().orEmpty()
            val customManifestUrl = myListsPrefs[stringPreferencesKey("manifest_url")]?.trim().orEmpty()
            val isExplicitlyPaired = myListsPrefs[booleanPreferencesKey("is_explicitly_paired")] == true

            val resolvedTargetId = if (activePid != 1 && !isExplicitlyPaired && targetId.isNotBlank() && targetId != MyListsDefaultSeed.DEFAULT_TARGET_ID) {
                val primaryTarget = factory.get(1, "my_lists_tv_sync").data.first()[stringPreferencesKey("my_lists_target_id")]?.trim().orEmpty()
                if (primaryTarget.isNotBlank() && targetId == primaryTarget) {
                    MyListsDefaultSeed.DEFAULT_TARGET_ID
                } else {
                    targetId
                }
            } else {
                targetId
            }

            val installedCustomUrl = if (activePid == 1 || isExplicitlyPaired) {
                list.firstOrNull {
                    MyListsDefaultSeed.isMyListsAddonUrl(it) && !MyListsDefaultSeed.isMyListsDefaultUrl(it)
                }
            } else null

            val activeProfileMyListsUrl = when {
                customManifestUrl.isNotBlank() && !MyListsDefaultSeed.isMyListsDefaultUrl(customManifestUrl) ->
                    canonicalizeUrl(customManifestUrl)
                installedCustomUrl != null ->
                    canonicalizeUrl(installedCustomUrl)
                resolvedTargetId.isNotBlank() && resolvedTargetId != MyListsDefaultSeed.DEFAULT_TARGET_ID ->
                    "https://mylistsaddon.com/$resolvedTargetId/tv"
                else ->
                    MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL
            }

            val withoutMyLists = list.filterNot { MyListsDefaultSeed.isMyListsAddonUrl(it) }
            val existingIndex = list.indexOfFirst { MyListsDefaultSeed.isMyListsAddonUrl(it) }
            val result = withoutMyLists.toMutableList()
            if (existingIndex in 0..result.size) {
                result.add(existingIndex, activeProfileMyListsUrl)
            } else {
                result.add(0, activeProfileMyListsUrl)
            }
            result.distinct()
        }
    }

    val addonEnabledStates: Flow<Map<String, Boolean>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            preferences[addonEnabledStatesKey]
                ?.let(::parseEnabledStateMap)
                .orEmpty()
        }
    }

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            val migrated = ensureMyListsAddonPresent(legacySet.toList())
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(migrated)
                preferences.remove(legacyUrlsKey)
            }
        } else {
            val current = parseUrlList(prefs[orderedUrlsKey]!!)
            val withMyLists = ensureMyListsAddonPresent(current)
            if (withMyLists != current) {
                ds.edit { preferences ->
                    preferences[orderedUrlsKey] = gson.toJson(withMyLists)
                }
            }
        }
    }

    suspend fun addAddon(url: String): Boolean {
           val active = profileManager.activeProfile
           if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            if (MyListsDefaultSeed.isMyListsAddonUrl(normalizedUrl) && !MyListsDefaultSeed.isMyListsDefaultUrl(normalizedUrl)) {
                val defaultIdx = current.indexOfFirst { MyListsDefaultSeed.isMyListsDefaultUrl(it) }
                if (defaultIdx != -1) {
                    val removedUrl = canonicalizeUrl(current[defaultIdx])
                    current[defaultIdx] = normalizedUrl
                    states.remove(removedUrl)
                } else {
                    current.add(0, normalizedUrl)
                }
            } else {
                current.add(normalizedUrl)
            }
            preferences[orderedUrlsKey] = gson.toJson(current)
            states[normalizedUrl] = true
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun removeAddon(url: String): Boolean {
           val active = profileManager.activeProfile
           if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        val normalizedTarget = canonicalizeUrl(url)
        if (MyListsDefaultSeed.isMyListsDefaultUrl(normalizedTarget)) {
            return false
        }
        var changed = false
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove == -1) return@edit
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states.remove(normalizedUrl)
            if (MyListsDefaultSeed.isMyListsAddonUrl(normalizedUrl)) {
                val hasOtherMyLists = current.withIndex().any { (idx, item) ->
                    idx != indexToRemove && MyListsDefaultSeed.isMyListsAddonUrl(item)
                }
                if (!hasOtherMyLists) {
                    current[indexToRemove] = MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL
                    states[MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL] = true
                } else {
                    current.removeAt(indexToRemove)
                }
            } else {
                current.removeAt(indexToRemove)
            }
            preferences[orderedUrlsKey] = gson.toJson(current)
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun setAddonOrder(urls: List<String>): Boolean {
            val active = profileManager.activeProfile
            if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val orderedUrls = urls.map(::canonicalizeUrl)
            val currentUrls = getCurrentList(preferences).map(::canonicalizeUrl)
            if (orderedUrls == currentUrls) return@edit
            preferences[orderedUrlsKey] = gson.toJson(orderedUrls)
            val currentStates = getCurrentEnabledStates(preferences)
            preferences[addonEnabledStatesKey] = gson.toJson(
                orderedUrls.associateWith { url -> currentStates[url] ?: true }
            )
            changed = true
        }
        return changed
    }

    suspend fun setAddonEnabled(url: String, enabled: Boolean): Boolean {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            val normalizedUrl = canonicalizeUrl(url)
            if ((states[normalizedUrl] ?: true) == enabled) return@edit
            states[normalizedUrl] = enabled
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun setAddonEnabledStates(states: Map<String, Boolean>) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            preferences[addonEnabledStatesKey] = gson.toJson(
                states.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        val list = if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
        return ensureMyListsAddonPresent(list)
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: getDefaultAddons().toList()
        } catch (e: Exception) {
            getDefaultAddons().toList()
        }
    }

    val userSetNames: Flow<Map<String, String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[userSetNamesKey]
            if (json != null) parseNameMap(json) else emptyMap()
        }
    }

    suspend fun setUserSetNames(names: Map<String, String>) {
        store().edit { preferences ->
            preferences[userSetNamesKey] = gson.toJson(
                names.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun parseNameMap(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getCurrentEnabledStates(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> {
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getDefaultAddons(): Set<String> = linkedSetOf(
        MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL,
        MyListsDefaultSeed.CINEMETA_BASE_URL,
        MyListsDefaultSeed.OPENSUBTITLES_BASE_URL
    )
}
