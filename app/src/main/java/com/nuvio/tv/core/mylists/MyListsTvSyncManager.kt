package com.nuvio.tv.core.mylists

import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.apps.INSTALLED_APPS_CATALOG_KEY
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class MyListsTvChannel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val category: String = "My Lists Addon Channels",
    val catalogId: String,
    val playOrder: Int = 0
)

@Keep
data class MyListsSyncResponse(
    val ok: Boolean = false,
    val versionHash: String? = null,
    val manifestUrl: String? = null,
    val collectionsUrl: String? = null,
    val randomizeItemsDaily: Boolean? = null,
    val catalogIds: List<String>? = null,
    val catalogOrder: List<String>? = null,
    val collections: JsonArray? = null,
    val channels: List<MyListsTvChannel>? = null
)

@Keep
data class MyListsDeviceCodeResponse(
    val ok: Boolean = false,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val error: String? = null
)

@Keep
data class MyListsDevicePollResponse(
    val ok: Boolean = false,
    val status: String? = null,
    val installId: String? = null,
    val manifestUrl: String? = null,
    val syncUrl: String? = null,
    val error: String? = null
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MyListsTvSyncManager @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val addonPreferences: AddonPreferences,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val authManager: dagger.Lazy<com.nuvio.tv.core.auth.AuthManager>,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "MyListsTvSyncManager"
        private const val FEATURE = "my_lists_tv_sync"
        const val DEFAULT_TARGET_ID = "default"
        private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L
        private const val MIN_AUTO_SYNC_INTERVAL_MS = 15_000L
        private const val MY_LISTS_USER_COLLECTION_ID = "my-lists-collections"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val gson = Gson()
    private var periodicSyncJob: Job? = null
    @Volatile
    private var lastSyncAttemptElapsedMs: Long = 0L

    private val targetIdKey = stringPreferencesKey("my_lists_target_id")
    private val lastVersionHashKey = stringPreferencesKey("last_version_hash")
    private val knownCatalogKeysKey = stringPreferencesKey("known_catalog_keys_json")
    private val knownCollectionIdsKey = stringPreferencesKey("known_collection_ids_json")
    private val channelsJsonKey = stringPreferencesKey("channels_json")

    private val _syncRefreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncRefreshEvents: SharedFlow<Unit> = _syncRefreshEvents.asSharedFlow()

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE)

    val targetIdFlow: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val effectivePid = if (profile != null && !profile.isPrimary && profile.usesPrimaryAddons) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            prefs[targetIdKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_TARGET_ID
        }
    }

    val channelsFlow: Flow<List<MyListsTvChannel>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val profile = profileManager.profiles.value.find { it.id == pid }
        val effectivePid = if (profile != null && !profile.isPrimary && profile.usesPrimaryAddons) 1 else pid
        factory.get(effectivePid, FEATURE).data.map { prefs ->
            val isCustom = (prefs[targetIdKey]?.trim() ?: DEFAULT_TARGET_ID) != DEFAULT_TARGET_ID
            parseChannels(prefs[channelsJsonKey], isCustomAccount = isCustom)
        }
    }

    fun startPeriodicSync() {
        requestSyncInBackground(force = false)
        if (periodicSyncJob?.isActive == true) return
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_SYNC_INTERVAL_MS)
                runCatching { syncIfNeeded(force = false) }
            }
        }
    }

    fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    fun requestSyncInBackground(force: Boolean = false) {
        scope.launch {
            runCatching { syncIfNeeded(force = force) }
        }
    }

    suspend fun setTargetId(targetId: String) {
        val normalized = targetId.trim().ifBlank { DEFAULT_TARGET_ID }
        store().edit { prefs ->
            val previous = prefs[targetIdKey] ?: DEFAULT_TARGET_ID
            prefs[targetIdKey] = normalized
            if (previous != normalized) {
                prefs.remove(lastVersionHashKey)
            }
        }
    }

    suspend fun linkAccount(targetId: String, manifestUrl: String? = null) {
        val normalizedTarget = targetId.trim().ifBlank { DEFAULT_TARGET_ID }
        store().edit { prefs ->
            prefs[targetIdKey] = normalizedTarget
            prefs.remove(lastVersionHashKey)
        }
        if (!manifestUrl.isNullOrBlank()) {
            replaceMyListsAddonUrl(manifestUrl)
        }
        syncIfNeeded(force = true)
    }

    suspend fun unlinkAccount() {
        store().edit { prefs ->
            prefs[targetIdKey] = DEFAULT_TARGET_ID
            prefs.remove(lastVersionHashKey)
            prefs[channelsJsonKey] = gson.toJson(MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS)
        }
        replaceMyListsAddonUrl(MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL)
        syncIfNeeded(force = true)
    }

    suspend fun requestDeviceCode(): Result<MyListsDeviceCodeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/device/code"
            val emptyJson = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
                .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .post(emptyJson)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty response from My Lists Addon (HTTP ${response.code})")
                val parsed = gson.fromJson(body, MyListsDeviceCodeResponse::class.java)
                if (!response.isSuccessful || parsed?.ok != true || parsed.deviceCode.isNullOrBlank()) {
                    throw IllegalStateException(parsed?.error ?: "Could not generate pairing code (HTTP ${response.code})")
                }
                parsed
            }
        }
    }

    suspend fun pollDeviceCode(deviceCode: String): Result<MyListsDevicePollResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(deviceCode.trim(), "UTF-8").replace("+", "%20")
            val url = "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/device/poll?deviceCode=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
                .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Empty poll response (HTTP ${response.code})")
                val parsed = gson.fromJson(body, MyListsDevicePollResponse::class.java)
                if (!response.isSuccessful || parsed?.ok != true) {
                    throw IllegalStateException(parsed?.error ?: "Device code expired or invalid (HTTP ${response.code})")
                }
                parsed
            }
        }
    }

    suspend fun syncIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && lastSyncAttemptElapsedMs > 0L && nowElapsed - lastSyncAttemptElapsedMs < MIN_AUTO_SYNC_INTERVAL_MS) {
            return@withContext false
        }
        syncMutex.withLock {
            val lockedNow = SystemClock.elapsedRealtime()
            if (!force && lastSyncAttemptElapsedMs > 0L && lockedNow - lastSyncAttemptElapsedMs < MIN_AUTO_SYNC_INTERVAL_MS) {
                return@withLock false
            }
            lastSyncAttemptElapsedMs = lockedNow

            val currentStore = store()
            val prefs = currentStore.data.first()
            val effectiveTarget = resolveEffectiveTargetId(prefs)
            val lastHash = prefs[lastVersionHashKey]

            val syncResponse = fetchSyncPayload(effectiveTarget) ?: return@withLock false
            val incomingHash = syncResponse.versionHash?.takeIf { it.isNotBlank() }
            val channelIdsSig = syncResponse.channels?.joinToString(",") { it.id }.orEmpty()
            val catalogOrderSig = (syncResponse.catalogOrder ?: syncResponse.catalogIds)?.joinToString(",").orEmpty()
            val effectiveIncomingHash = listOfNotNull(
                incomingHash,
                channelIdsSig.takeIf { it.isNotBlank() },
                catalogOrderSig.takeIf { it.isNotBlank() }
            ).joinToString("::")

            if (!force && effectiveIncomingHash.isNotBlank() && effectiveIncomingHash == lastHash) {
                Log.d(TAG, "My Lists sync up-to-date (target=$effectiveTarget hash=$effectiveIncomingHash)")
                return@withLock false
            }

            Log.d(
                TAG,
                "Applying My Lists sync update target=$effectiveTarget oldHash=$lastHash newHash=$effectiveIncomingHash force=$force"
            )

            // 1. Hot-reload manifest & update installed My Lists addon URL in-place if needed
            val targetManifestUrl = syncResponse.manifestUrl?.takeIf { it.isNotBlank() }
                ?: if (effectiveTarget == DEFAULT_TARGET_ID) {
                    MyListsDefaultSeed.MY_LISTS_DEFAULT_MANIFEST_URL
                } else {
                    "https://mylistsaddon.com/$effectiveTarget/manifest.json"
                }

            var refreshedAddon: Addon? = null
            if (targetManifestUrl != null) {
                val cleanBaseUrl = canonicalizeManifestBaseUrl(targetManifestUrl)
                replaceMyListsAddonUrl(cleanBaseUrl)
                when (val fetchResult = addonRepository.fetchAddon(cleanBaseUrl)) {
                    is NetworkResult.Success -> refreshedAddon = fetchResult.data
                    else -> Log.w(TAG, "Manifest hot-reload failed for $cleanBaseUrl, using installed state")
                }
            }

            if (refreshedAddon == null) {
                refreshedAddon = addonRepository.getInstalledAddons().first()
                    .firstOrNull { MyListsDefaultSeed.isMyListsAddon(it) }
            }

            // 2. Sync Premade & User Collections into CollectionsDataStore
            val previousKnownCollectionIds = parseStringList(prefs[knownCollectionIdsKey])
                .ifEmpty { MyListsDefaultSeed.DEFAULT_COLLECTION_IDS }
            val collectionsArray = syncResponse.collections
                ?: fetchCollectionsFromUrl(syncResponse.collectionsUrl)
            val syncedCollectionIds = if (authManager.get().isAuthenticated) emptyList() else applyCollectionsSync(
                collectionsArray = collectionsArray,
                previousKnownCollectionIds = previousKnownCollectionIds
            )

            // 3. Diff catalog IDs: auto-enable newly added lists and prune removed lists
            val previousKnownCatalogKeys = parseStringList(prefs[knownCatalogKeysKey])
                .ifEmpty { MyListsDefaultSeed.DEFAULT_HOME_CATALOG_KEYS }
            val newCatalogKeys = if (refreshedAddon != null) {
                applyCatalogDiffAndOrder(
                    addon = refreshedAddon,
                    syncCatalogOrder = syncResponse.catalogOrder ?: syncResponse.catalogIds,
                    previousKnownCatalogKeys = previousKnownCatalogKeys,
                    syncedCollectionIds = syncedCollectionIds,
                    previousKnownCollectionIds = previousKnownCollectionIds
                )
            } else {
                previousKnownCatalogKeys
            }

            // 4. Persist channels, versionHash, and known catalog/collection keys
            val isCustomAccount = effectiveTarget != DEFAULT_TARGET_ID
            val channelsFromSync = if (isCustomAccount) {
                syncResponse.channels.orEmpty()
            } else {
                syncResponse.channels?.takeIf { it.isNotEmpty() }
                    ?: MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS
            }
            val manifestChannelCatalogs = if (isCustomAccount && channelsFromSync.isEmpty()) {
                emptyList()
            } else {
                refreshedAddon?.catalogs?.filter {
                    it.id.startsWith("channel_") || it.apiType == "channel"
                }.orEmpty()
            }
            val extraChannels = manifestChannelCatalogs.map { cat ->
                MyListsTvChannel(
                    id = cat.id,
                    name = cat.name,
                    category = "My Lists Addon Channels",
                    catalogId = cat.id
                )
            }
            val channelsToStore = if (isCustomAccount && channelsFromSync.isEmpty() && extraChannels.isEmpty()) {
                emptyList()
            } else {
                normalizeChannels((channelsFromSync + extraChannels).distinctBy { it.id })
            }
            currentStore.edit { editPrefs ->
                editPrefs[targetIdKey] = effectiveTarget
                if (effectiveIncomingHash.isNotBlank()) {
                    editPrefs[lastVersionHashKey] = effectiveIncomingHash
                }
                editPrefs[knownCatalogKeysKey] = gson.toJson(newCatalogKeys)
                editPrefs[knownCollectionIdsKey] = gson.toJson(syncedCollectionIds)
                editPrefs[channelsJsonKey] = gson.toJson(channelsToStore)
            }

            _syncRefreshEvents.tryEmit(Unit)
            true
        }
    }

    private suspend fun resolveEffectiveTargetId(prefs: Preferences): String {
        val storedTarget = prefs[targetIdKey]?.trim().orEmpty()
        if (storedTarget.isNotBlank() && storedTarget != DEFAULT_TARGET_ID) {
            return storedTarget
        }
        // If the user installed a personal mylistsaddon.com URL in AddonManager, infer its token.
        val installedUrls = addonPreferences.installedAddonUrls.first()
        val customMyListsUrl = installedUrls.firstOrNull {
            MyListsDefaultSeed.isMyListsAddonUrl(it) && !MyListsDefaultSeed.isMyListsDefaultUrl(it)
        }
        if (customMyListsUrl != null) {
            val extracted = extractTargetFromManifestUrl(customMyListsUrl)
            if (!extracted.isNullOrBlank()) {
                return extracted
            }
        }
        return DEFAULT_TARGET_ID
    }

    private fun extractTargetFromManifestUrl(url: String): String? {
        val clean = canonicalizeManifestBaseUrl(url)
        val path = clean.removePrefix(MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN).trim('/')
        if (path.isBlank() || path.equals("nuvio-default", ignoreCase = true)) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        return when {
            segments.size >= 2 && (segments[0] == "u" || segments[0] == "i") -> segments[1]
            segments.size == 1 -> segments[0]
            else -> null
        }
    }

    private fun fetchSyncPayload(targetId: String): MyListsSyncResponse? {
        val encodedTarget = URLEncoder.encode(targetId, "UTF-8").replace("+", "%20")
        val url = "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/sync/$encodedTarget"
        val request = Request.Builder()
            .url(url)
            .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
            .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Sync endpoint returned HTTP ${response.code} for url=$url")
                    return null
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                val parsed = gson.fromJson(body, MyListsSyncResponse::class.java)
                if (parsed?.ok == true) parsed else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to reach My Lists sync endpoint: ${e.message}")
            null
        }
    }

    private fun fetchCollectionsFromUrl(url: String?): JsonArray? {
        val targetUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val request = Request.Builder()
            .url(targetUrl)
            .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
            .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Collections endpoint returned HTTP ${response.code} for url=$targetUrl")
                    return null
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                val obj = JSONObject(body)
                val collectionsJson = obj.optJSONArray("collections")?.toString() ?: return null
                gson.fromJson(collectionsJson, JsonArray::class.java)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to reach My Lists collections endpoint: ${e.message}")
            null
        }
    }

    private suspend fun replaceMyListsAddonUrl(newUrl: String) {
        val cleanNew = canonicalizeManifestBaseUrl(newUrl)
        val currentUrls = addonPreferences.installedAddonUrls.first()
        val existingMyListsIndex = currentUrls.indexOfFirst { MyListsDefaultSeed.isMyListsAddonUrl(it) }
        if (existingMyListsIndex == -1) {
            addonPreferences.addAddon(cleanNew)
            return
        }
        val existingUrl = canonicalizeManifestBaseUrl(currentUrls[existingMyListsIndex])
        if (existingUrl.equals(cleanNew, ignoreCase = true)) {
            return
        }
        val updatedUrls = currentUrls.toMutableList()
        updatedUrls[existingMyListsIndex] = cleanNew
        addonPreferences.setAddonOrder(updatedUrls.distinct())
    }

    private suspend fun applyCollectionsSync(
        collectionsArray: JsonArray?,
        previousKnownCollectionIds: List<String>
    ): List<String> {
        if (collectionsArray == null) {
            return previousKnownCollectionIds
        }
        val json = gson.toJson(collectionsArray)
        val syncedCollections = collectionsDataStore.importFromJson(json)
        if (syncedCollections.isEmpty() && collectionsArray.size() > 0) {
            return previousKnownCollectionIds
        }

        val enrichedSynced = syncedCollections
            .filterNot { it.id in MyListsDefaultSeed.REMOVED_COLLECTION_IDS }
            .map { col ->
                val enrichedFolders = col.folders.map { folder ->
                    val resolvedCover = MyListsDefaultSeed.resolveCollectionFolderCoverImage(folder.id, folder.coverImageUrl)
                    if (resolvedCover != folder.coverImageUrl) {
                        folder.copy(coverImageUrl = resolvedCover)
                    } else {
                        folder
                    }
                }
                col.copy(folders = enrichedFolders)
            }

        val currentCollections = collectionsDataStore.getCurrentCollections()
        val managedIds = (
            previousKnownCollectionIds +
                MyListsDefaultSeed.DEFAULT_COLLECTION_IDS +
                MyListsDefaultSeed.REMOVED_COLLECTION_IDS +
                MY_LISTS_USER_COLLECTION_ID +
                enrichedSynced.map { it.id }
            ).toSet()
        val preservedLocalCollections = currentCollections
            .filterNot { it.id in managedIds }
            .filterNot { it.id in MyListsDefaultSeed.REMOVED_COLLECTION_IDS }
        val mergedCollections = (enrichedSynced + preservedLocalCollections)
            .filterNot { it.id in MyListsDefaultSeed.REMOVED_COLLECTION_IDS }
            .associateBy { it.id }
            .values
            .toList()

        if (mergedCollections != currentCollections) {
            collectionsDataStore.setCollections(mergedCollections)
        }
        return enrichedSynced.map { it.id }
    }

    private suspend fun applyCatalogDiffAndOrder(
        addon: Addon,
        syncCatalogOrder: List<String>?,
        previousKnownCatalogKeys: List<String>,
        syncedCollectionIds: List<String>,
        previousKnownCollectionIds: List<String>
    ): List<String> {
        val homeCatalogs = addon.catalogs.filterNot { it.isSearchOnlyCatalog() }
        val orderRank = syncCatalogOrder
            ?.withIndex()
            ?.associate { (idx, id) -> id to idx }
            .orEmpty()

        val sortedCatalogs = if (orderRank.isNotEmpty()) {
            homeCatalogs.sortedWith(
                compareBy<com.nuvio.tv.domain.model.CatalogDescriptor> { orderRank[it.id] ?: Int.MAX_VALUE }
            )
        } else {
            homeCatalogs
        }

        val newMyListsCatalogKeys = sortedCatalogs
            .map { descriptor -> homeCatalogKey(addon.id, descriptor.apiType, descriptor.id) }
            .distinct()

        val previousSet = previousKnownCatalogKeys.toSet()
        val newSet = newMyListsCatalogKeys.toSet()
        val addedCatalogKeys = newMyListsCatalogKeys.filter { it !in previousSet }
        val extraRemovedCatalogKeys = setOf(
            homeCatalogKey(addon.id, "series", "chart:trakt:boxoffice_tv"),
            "${addon.id}_series_chart:trakt:boxoffice_tv",
            "${addon.baseUrl}_series_chart:trakt:boxoffice_tv_Trakt Box Office"
        )
        val isCustom = !MyListsDefaultSeed.isMyListsDefaultUrl(addon.baseUrl)
        val defaultSeedKeys = MyListsDefaultSeed.DEFAULT_HOME_CATALOG_KEYS.toSet()
        val removedCatalogKeys = (
            previousKnownCatalogKeys.filter { it !in newSet } +
                extraRemovedCatalogKeys +
                (if (isCustom) defaultSeedKeys.filter { it !in newSet } else emptyList())
        ).toSet()

        // Auto-enable any newly added My Lists catalogs and ensure removed keys are disabled
        val currentDisabled = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toMutableSet()
        val disabledRemoved = currentDisabled.removeAll(addedCatalogKeys.toSet())
        val disabledAdded = currentDisabled.addAll(extraRemovedCatalogKeys)
        if (disabledRemoved || disabledAdded) {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(currentDisabled.toList())
        }

        // Update Home Layout ordering:
        // - Keep collections first (inserting any new My Lists collections at the top of collections)
        // - Prune removed My Lists collections and removed My Lists catalogs
        // - Append/insert newly added My Lists catalogs in manifest/sync order before Installed Apps
        val syncedCollectionKeys = syncedCollectionIds.map { homeCollectionKey(it) }
        val removedCollectionKeys = (
            previousKnownCollectionIds.filter { it !in syncedCollectionIds } +
                MyListsDefaultSeed.REMOVED_COLLECTION_IDS
            )
            .map { homeCollectionKey(it) }
            .toSet()

        val currentOrder = layoutPreferenceDataStore.homeCatalogOrderKeys.first()
        val prunedOrder = currentOrder.filterNot { key ->
            key in removedCatalogKeys ||
                key in removedCollectionKeys ||
                (isCustom && newSet.isEmpty() && (
                    MyListsDefaultSeed.isMyListsDefaultCatalogKey(key) ||
                    key.startsWith("app.my-list") ||
                    key.contains("mylistsaddon.com")
                ))
        }

        val updatedOrder = buildUpdatedHomeOrder(
            currentOrder = prunedOrder,
            syncedCollectionKeys = syncedCollectionKeys,
            newMyListsCatalogKeys = newMyListsCatalogKeys,
            previousMyListsCatalogKeys = previousKnownCatalogKeys
        )

        if (updatedOrder != currentOrder) {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(updatedOrder)
        }

        return newMyListsCatalogKeys
    }

    private fun buildUpdatedHomeOrder(
        currentOrder: List<String>,
        syncedCollectionKeys: List<String>,
        newMyListsCatalogKeys: List<String>,
        previousMyListsCatalogKeys: List<String>
    ): List<String> {
        val result = currentOrder.toMutableList()

        // 1. Ensure all synced My Lists collection keys are present (leading at the top if new)
        val missingCollections = syncedCollectionKeys.filter { it !in result }
        if (missingCollections.isNotEmpty()) {
            val firstCollectionIdx = result.indexOfFirst { it.startsWith("collection_") }
            val insertAt = if (firstCollectionIdx >= 0) firstCollectionIdx else 0
            result.addAll(insertAt, missingCollections)
        }

        // 2. If the user hasn't manually interleaved other addons' catalogs among My Lists catalogs,
        // or if new My Lists catalogs were added on the website, update/insert them in order.
        val existingMyListsInOrder = result.filter { it in newMyListsCatalogKeys }
        val missingCatalogs = newMyListsCatalogKeys.filter { it !in result }

        if (existingMyListsInOrder != newMyListsCatalogKeys && existingMyListsInOrder == previousMyListsCatalogKeys.filter { it in newMyListsCatalogKeys }) {
            // Website catalog order changed: replace existing My Lists slots in-place and append any new ones
            val firstMyListsIdx = result.indexOfFirst { it in newMyListsCatalogKeys }
            if (firstMyListsIdx >= 0) {
                result.removeAll(newMyListsCatalogKeys.toSet())
                val clampedIdx = firstMyListsIdx.coerceAtMost(result.size)
                result.addAll(clampedIdx, newMyListsCatalogKeys)
            } else {
                val appsIdx = result.indexOf(INSTALLED_APPS_CATALOG_KEY)
                val insertAt = if (appsIdx >= 0) appsIdx else result.size
                result.addAll(insertAt, newMyListsCatalogKeys)
            }
        } else if (missingCatalogs.isNotEmpty()) {
            val lastMyListsIdx = result.indexOfLast { it in newMyListsCatalogKeys }
            val appsIdx = result.indexOf(INSTALLED_APPS_CATALOG_KEY)
            val insertAt = when {
                lastMyListsIdx >= 0 -> lastMyListsIdx + 1
                appsIdx >= 0 -> appsIdx
                else -> result.size
            }
            result.addAll(insertAt, missingCatalogs)
        }

        return result.distinct()
    }

    private fun canonicalizeManifestBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeChannels(channels: List<MyListsTvChannel>): List<MyListsTvChannel> {
        return channels
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .sortedWith(compareBy<MyListsTvChannel> { if (it.playOrder > 0) it.playOrder else Int.MAX_VALUE }.thenBy { it.name })
            .mapIndexed { idx, ch ->
                val category = if (ch.category.isBlank() || ch.category.equals("Live TV", ignoreCase = true) || ch.category.equals("My Lists Channels", ignoreCase = true)) {
                    "My Lists Addon Channels"
                } else {
                    ch.category.trim()
                }
                val resolvedLogo = MyListsDefaultSeed.resolveChannelLogoUrl(ch.logoUrl, ch.name)
                val resolvedPoster = ch.posterUrl?.takeIf { it.isNotBlank() }
                    ?.let { MyListsDefaultSeed.resolveChannelPosterUrl(ch.name, it) }
                    ?: MyListsDefaultSeed.resolveChannelPosterUrl(ch.name)
                ch.copy(
                    logoUrl = resolvedLogo,
                    posterUrl = resolvedPoster,
                    backdropUrl = ch.backdropUrl?.takeIf { it.isNotBlank() },
                    category = category,
                    catalogId = ch.catalogId.takeIf { it.isNotBlank() } ?: ch.id,
                    playOrder = if (ch.playOrder > 0) ch.playOrder else idx + 1
                )
            }
    }

    private fun parseChannels(json: String?, isCustomAccount: Boolean = false): List<MyListsTvChannel> {
        if (json == null) return if (isCustomAccount) emptyList() else MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS
        if (json.trim() == "[]" || json.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<MyListsTvChannel>>() {}.type
            val parsed = gson.fromJson<List<MyListsTvChannel>>(json, type).orEmpty()
            if (parsed.isEmpty()) {
                if (isCustomAccount) emptyList() else MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS
            } else {
                normalizeChannels(parsed)
            }
        } catch (_: Exception) {
            if (isCustomAccount) emptyList() else MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS
        }
    }
}
