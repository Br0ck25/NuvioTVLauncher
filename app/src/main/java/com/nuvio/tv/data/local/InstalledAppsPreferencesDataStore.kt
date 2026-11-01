package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.apps.moveInstalledApp
import com.nuvio.tv.core.apps.orderInstalledApps
import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InstalledAppsPreferences(
    val order: List<String> = emptyList(),
    val removedPackages: Set<String> = emptySet()
)

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstalledAppsPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val orderKey = stringPreferencesKey("installed_apps_order")
    private val removedKey = stringPreferencesKey("installed_apps_removed")
    private val gson = Gson()
    private val writeMutex = Mutex()
    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, "installed_apps_row")

    val preferences: Flow<InstalledAppsPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, "installed_apps_row").data.map { prefs ->
            InstalledAppsPreferences(
                order = parsePackages(prefs[orderKey]),
                removedPackages = parsePackages(prefs[removedKey]).toSet()
            )
        }
    }

    suspend fun moveApp(packageName: String, direction: Int, availablePackages: List<String>) = mutate { prefs ->
        prefs.copy(order = moveInstalledApp(orderInstalledApps(availablePackages, prefs.order, prefs.removedPackages), packageName, direction))
    }

    suspend fun removeFromRow(packageName: String, availablePackages: List<String>) = mutate { prefs ->
        prefs.copy(
            order = orderInstalledApps(availablePackages, prefs.order, prefs.removedPackages).filterNot { it == packageName },
            removedPackages = prefs.removedPackages + packageName
        )
    }

    suspend fun restoreToRow(packageName: String, availablePackages: List<String>) = mutate { prefs ->
        val removed = prefs.removedPackages - packageName
        prefs.copy(order = orderInstalledApps(availablePackages, prefs.order, removed), removedPackages = removed)
    }

    suspend fun restoreAll(availablePackages: List<String>) = mutate { prefs ->
        prefs.copy(order = orderInstalledApps(availablePackages, prefs.order, emptySet()), removedPackages = emptySet())
    }

    suspend fun currentPreferences(): InstalledAppsPreferences = preferences.first()

    private suspend fun mutate(transform: (InstalledAppsPreferences) -> InstalledAppsPreferences) = writeMutex.withLock {
        // Capture the profile before suspension so a profile switch cannot write edits to the wrong store.
        val targetStore = store()
        val current = targetStore.data.first().let { prefs ->
            InstalledAppsPreferences(parsePackages(prefs[orderKey]), parsePackages(prefs[removedKey]).toSet())
        }
        val updated = transform(current)
        targetStore.edit { prefs ->
            prefs[orderKey] = gson.toJson(updated.order.distinct())
            prefs[removedKey] = gson.toJson(updated.removedPackages.toList())
        }
    }

    private fun parsePackages(json: String?): List<String> = runCatching {
        gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
            .orEmpty().filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
}
