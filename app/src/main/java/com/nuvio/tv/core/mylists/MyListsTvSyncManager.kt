package com.nuvio.tv.core.mylists

import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AuthState
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
import kotlinx.coroutines.flow.combine
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
    val channels: List<MyListsTvChannel>? = null,
    val betterPosters: Boolean? = null,
    val customPosterPattern: String? = null
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
    private val manifestUrlKey = stringPreferencesKey("manifest_url")
    private val isExplicitlyPairedKey = booleanPreferencesKey("is_explicitly_paired")
    private val lastVersionHashKey = stringPreferencesKey("last_version_hash")
    private val knownCatalogKeysKey = stringPreferencesKey("known_catalog_keys_json")
    private val knownCollectionIdsKey = stringPreferencesKey("known_collection_ids_json")
    private val channelsJsonKey = stringPreferencesKey("channels_json")

    private val _syncRefreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncRefreshEvents: SharedFlow<Unit> = _syncRefreshEvents.asSharedFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                Log.d(TAG, "Active profile changed to $profileId: resetting rate limit and requesting sync")
                lastSyncAttemptElapsedMs = 0L
                runCatching {
                    syncIfNeeded(force = true)
                }
            }
        }
        scope.launch {
            authManager.get().authState.collect { authState ->
                if (authState is AuthState.FullAccount) {
                    Log.d(TAG, "Auth state changed to FullAccount: resetting rate limit and requesting sync")
                    lastSyncAttemptElapsedMs = 0L
                    runCatching {
                        syncIfNeeded(force = true)
                    }
                }
            }
        }
    }

    private fun currentProfileId(): Int = profileManager.activeProfileId.value

    private fun store(profileId: Int = currentProfileId()) =
        factory.get(profileId, FEATURE)

    val targetIdFlow: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val stored = prefs[targetIdKey]?.trim().orEmpty()
            if (stored.isNotBlank() && stored != DEFAULT_TARGET_ID) {
                if (pid != 1 && prefs[isExplicitlyPairedKey] != true) {
                    val primaryTarget = factory.get(1, FEATURE).data.first()[targetIdKey]?.trim().orEmpty()
                    if (primaryTarget.isNotBlank() && primaryTarget != DEFAULT_TARGET_ID && stored == primaryTarget) {
                        return@map DEFAULT_TARGET_ID
                    }
                }
                stored
            } else {
                DEFAULT_TARGET_ID
            }
        }
    }

    val channelsFlow: Flow<List<MyListsTvChannel>> = combine(
        profileManager.activeProfileId,
        targetIdFlow
    ) { pid, targetId ->
        pid to targetId
    }.flatMapLatest { (pid, targetId) ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val isCustom = targetId.isNotBlank() && targetId != DEFAULT_TARGET_ID
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
                if (normalized == DEFAULT_TARGET_ID) {
                    prefs.remove(manifestUrlKey)
                    prefs.remove(isExplicitlyPairedKey)
                }
            }
        }
    }

    suspend fun linkAccount(targetId: String, manifestUrl: String? = null) {
        val normalizedTarget = targetId.trim().ifBlank { DEFAULT_TARGET_ID }
        val cleanManifest = manifestUrl?.takeIf { it.isNotBlank() }?.let(::canonicalizeManifestBaseUrl)
        store().edit { prefs ->
            prefs[targetIdKey] = normalizedTarget
            prefs[isExplicitlyPairedKey] = true
            if (cleanManifest != null) {
                prefs[manifestUrlKey] = cleanManifest
            } else {
                prefs.remove(manifestUrlKey)
            }
            prefs.remove(lastVersionHashKey)
        }
        replaceMyListsAddonUrl(cleanManifest ?: "https://mylistsaddon.com/$normalizedTarget/tv")
        syncIfNeeded(force = true)
    }

    suspend fun unlinkAccount() {
        val currentPattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
        if (currentPattern.contains("btttr.cc") || currentPattern.contains("mylistsaddon.com/bp")) {
            layoutPreferenceDataStore.clearCustomPosterSettings()
        }
        store().edit { prefs ->
            prefs[targetIdKey] = DEFAULT_TARGET_ID
            prefs.remove(manifestUrlKey)
            prefs.remove(isExplicitlyPairedKey)
            prefs.remove(lastVersionHashKey)
            prefs[channelsJsonKey] = gson.toJson(MyListsDefaultSeed.DEFAULT_QUICK_ADD_CHANNELS)
        }
        replaceMyListsAddonUrl(MyListsDefaultSeed.MY_LISTS_DEFAULT_BASE_URL)
        syncIfNeeded(force = true)
    }

    fun reportWatchHistory(
        type: String,
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        progressPercent: Double = 100.0,
        completed: Boolean = true,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        scope.launch {
            runCatching {
                val store = store(profileId)
                val prefs = store.data.first()
                val targetId = prefs[targetIdKey]?.trim().orEmpty()
                if (targetId.isBlank() || targetId == DEFAULT_TARGET_ID) {
                    return@runCatching
                }

                val imdbId = if (contentId.startsWith("tt", ignoreCase = true)) {
                    contentId.substringBefore(':')
                } else null
                val tmdbId = if (!contentId.startsWith("tt", ignoreCase = true)) {
                    contentId.substringBefore(':').toIntOrNull()
                } else null

                val json = JSONObject().apply {
                    put("targetId", targetId)
                    put("type", if (type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)) "series" else "movie")
                    put("contentId", contentId)
                    if (imdbId != null) put("imdbId", imdbId)
                    if (tmdbId != null) put("tmdbId", tmdbId)
                    if (season != null) put("season", season)
                    if (episode != null) put("episode", episode)
                    put("progress", progressPercent)
                    put("completed", completed)
                    put("watchedAt", System.currentTimeMillis())
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/history")
                    .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
                    .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
                    .header("X-Nuvio-Client", "NuvioTVLauncher")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Watch history report returned HTTP ${response.code} for $contentId")
                    } else {
                        Log.d(TAG, "Watch history successfully reported for $contentId (completed=$completed progress=$progressPercent)")
                    }
                }
            }
        }
    }

    fun reportWatchHistoryRemoval(
        type: String,
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        scope.launch {
            runCatching {
                val store = store(profileId)
                val prefs = store.data.first()
                val targetId = prefs[targetIdKey]?.trim().orEmpty()
                if (targetId.isBlank() || targetId == DEFAULT_TARGET_ID) {
                    return@runCatching
                }

                val imdbId = if (contentId.startsWith("tt", ignoreCase = true)) {
                    contentId.substringBefore(':')
                } else null
                val tmdbId = if (!contentId.startsWith("tt", ignoreCase = true)) {
                    contentId.substringBefore(':').toIntOrNull()
                } else null

                val json = JSONObject().apply {
                    put("targetId", targetId)
                    put("type", if (type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)) "series" else "movie")
                    put("contentId", contentId)
                    if (imdbId != null) put("imdbId", imdbId)
                    if (tmdbId != null) put("tmdbId", tmdbId)
                    if (season != null) put("season", season)
                    if (episode != null) put("episode", episode)
                    put("action", "remove")
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/history/remove")
                    .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
                    .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
                    .header("X-Nuvio-Client", "NuvioTVLauncher")
                    .post(body)
                    .build()

                okHttpClient.newCall(request).execute().close()
            }
        }
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
        val syncProfileId = profileManager.activeProfileId.value
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && lastSyncAttemptElapsedMs > 0L && nowElapsed - lastSyncAttemptElapsedMs < MIN_AUTO_SYNC_INTERVAL_MS) {
            return@withContext false
        }
        syncMutex.withLock {
            if (profileManager.activeProfileId.value != syncProfileId) {
                return@withLock false
            }
            val lockedNow = SystemClock.elapsedRealtime()
            if (!force && lastSyncAttemptElapsedMs > 0L && lockedNow - lastSyncAttemptElapsedMs < MIN_AUTO_SYNC_INTERVAL_MS) {
                return@withLock false
            }
            lastSyncAttemptElapsedMs = lockedNow

            val currentStore = factory.get(syncProfileId, FEATURE)
            val prefs = currentStore.data.first()
            val effectiveTarget = resolveEffectiveTargetId(prefs, syncProfileId)
            val lastHash = prefs[lastVersionHashKey]

            // Sends the tag of the payload last applied, so an unchanged one comes back as a bare 304.
            // A forced sync always downloads it in full.
            val fetched = fetchSyncPayload(effectiveTarget, ifNoneMatch = if (force) null else syncEtags[syncEtagKey(syncProfileId, effectiveTarget)])
            if (fetched.notModified) {
                Log.d(TAG, "My Lists sync not modified (profile=$syncProfileId target=$effectiveTarget)")
                return@withLock false
            }
            val syncResponse = fetched.payload ?: return@withLock false
            val incomingEtag = fetched.etag
            val incomingHash = syncResponse.versionHash?.takeIf { it.isNotBlank() }
            val channelIdsSig = syncResponse.channels?.joinToString(",") { it.id }.orEmpty()
            val catalogOrderSig = (syncResponse.catalogOrder ?: syncResponse.catalogIds)?.joinToString(",").orEmpty()
            val manifestUrlSig = syncResponse.manifestUrl?.trim().orEmpty()
            val effectiveIncomingHash = listOfNotNull(
                incomingHash,
                manifestUrlSig.takeIf { it.isNotBlank() },
                channelIdsSig.takeIf { it.isNotBlank() },
                catalogOrderSig.takeIf { it.isNotBlank() }
            ).joinToString("::")

            if (!force && effectiveIncomingHash.isNotBlank() && effectiveIncomingHash == lastHash) {
                Log.d(TAG, "My Lists sync up-to-date (profile=$syncProfileId target=$effectiveTarget hash=$effectiveIncomingHash)")
                incomingEtag?.let { syncEtags[syncEtagKey(syncProfileId, effectiveTarget)] = it }
                return@withLock false
            }

            Log.d(
                TAG,
                "Applying My Lists sync update profile=$syncProfileId target=$effectiveTarget oldHash=$lastHash newHash=$effectiveIncomingHash force=$force"
            )

            // 1. Hot-reload manifest & update installed My Lists addon URL in-place if needed
            val targetManifestUrl = syncResponse.manifestUrl?.takeIf { it.isNotBlank() }
                ?: prefs[manifestUrlKey]?.takeIf { it.isNotBlank() }
                ?: if (effectiveTarget == DEFAULT_TARGET_ID) {
                    MyListsDefaultSeed.MY_LISTS_DEFAULT_MANIFEST_URL
                } else {
                    "https://mylistsaddon.com/$effectiveTarget/tv/manifest.json"
                }

            var refreshedAddon: Addon? = null
            if (targetManifestUrl != null) {
                val cleanBaseUrl = canonicalizeManifestBaseUrl(targetManifestUrl)
                replaceMyListsAddonUrl(cleanBaseUrl)
                when (val fetchResult = addonRepository.fetchAddon(cleanBaseUrl, cacheControl = "no-cache")) {
                    is NetworkResult.Success -> refreshedAddon = fetchResult.data
                    else -> Log.w(TAG, "Manifest hot-reload failed for $cleanBaseUrl, using installed state")
                }
                cleanUpLegacyAddon(cleanBaseUrl)
            }

            if (profileManager.activeProfileId.value != syncProfileId) {
                Log.d(TAG, "Profile changed during sync ($syncProfileId -> ${profileManager.activeProfileId.value}), aborting commit")
                return@withLock false
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
                .map { MyListsDefaultSeed.migrateCatalogKey(it) }
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

            if (profileManager.activeProfileId.value != syncProfileId) {
                Log.d(TAG, "Profile changed before edit commit ($syncProfileId -> ${profileManager.activeProfileId.value}), aborting")
                return@withLock false
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

            // 5. Better Posters synchronization
            var hasBetterPosters = syncResponse.betterPosters == true
            // A server that reports the setting (it sends the field, true or false) needs no probe; an older
            // one is asked the old way, by looking for its poster URLs in a catalog.
            if (syncResponse.betterPosters == null && !hasBetterPosters && isCustomAccount && refreshedAddon != null) {
                val firstCatalog = refreshedAddon.catalogs.firstOrNull { !it.isSearchOnlyCatalog() }
                if (firstCatalog != null) {
                    val probeUrl = "${refreshedAddon.baseUrl}/catalog/${firstCatalog.apiType}/${firstCatalog.id}.json"
                    try {
                        val req = Request.Builder()
                            .url(probeUrl)
                            .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
                            .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .get()
                            .build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val respBody = resp.body?.string().orEmpty()
                                hasBetterPosters = respBody.contains("/bp/") || respBody.contains("btttr.cc")
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Better Posters probe failed: ${e.message}")
                    }
                }
            }

            if (isCustomAccount && hasBetterPosters) {
                val currentPattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                if (currentPattern.isBlank() || currentPattern.contains("btttr.cc") || currentPattern.contains("mylistsaddon.com/bp")) {
                    val targetPattern = syncResponse.customPosterPattern?.takeIf { it.isNotBlank() }
                        ?: "https://btttr.cc/poster/imdb/poster-default/{imdb_id}.jpg"
                    if (currentPattern != targetPattern) {
                        layoutPreferenceDataStore.setCustomPosterUrlPattern(targetPattern)
                        layoutPreferenceDataStore.setCustomPosterEnabledScreens(CustomPosterScreen.ALL)
                        Log.i(TAG, "Auto-configured Better Posters pattern: $targetPattern across all screens")
                    }
                }
            } else if (isCustomAccount && !hasBetterPosters) {
                val currentPattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                if (currentPattern.contains("btttr.cc") || currentPattern.contains("mylistsaddon.com/bp")) {
                    layoutPreferenceDataStore.clearCustomPosterSettings()
                    Log.i(TAG, "Cleared Better Posters pattern as it was disabled in My Lists account")
                }
            }

            // Remembered only now that the payload is applied: a sync that stopped half way must not be
            // answered "not modified" next time.
            incomingEtag?.let { syncEtags[syncEtagKey(syncProfileId, effectiveTarget)] = it }
            _syncRefreshEvents.tryEmit(Unit)
            true
        }
    }

    private suspend fun resolveEffectiveTargetId(prefs: Preferences, profileId: Int): String {
        val storedTarget = prefs[targetIdKey]?.trim().orEmpty()
        if (storedTarget.isNotBlank() && storedTarget != DEFAULT_TARGET_ID) {
            // Guard against legacy leak: if this is a secondary profile with the same targetId as profile 1
            // and it was never explicitly paired by the user on this profile, reset to DEFAULT_TARGET_ID.
            if (profileId != 1 && prefs[isExplicitlyPairedKey] != true) {
                val primaryStore = factory.get(1, FEATURE)
                val primaryTarget = primaryStore.data.first()[targetIdKey]?.trim().orEmpty()
                if (primaryTarget.isNotBlank() && primaryTarget != DEFAULT_TARGET_ID && storedTarget == primaryTarget) {
                    Log.w(TAG, "Profile $profileId had leaked targetId from primary profile ($storedTarget), resetting to default")
                    store(profileId).edit {
                        it[targetIdKey] = DEFAULT_TARGET_ID
                        it.remove(manifestUrlKey)
                        it.remove(lastVersionHashKey)
                        it.remove(isExplicitlyPairedKey)
                    }
                    return DEFAULT_TARGET_ID
                }
            }
            return storedTarget
        }
        return DEFAULT_TARGET_ID
    }

    private fun extractTargetFromManifestUrl(url: String): String? {
        val clean = canonicalizeManifestBaseUrl(url)
        val path = clean.removePrefix(MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN).trim('/')
        if (path.isBlank() || path.equals("nuvio-default", ignoreCase = true) || path.equals("nuvio-default/tv", ignoreCase = true)) return null
        val segments = path.split('/').filter { it.isNotBlank() && !it.equals("tv", ignoreCase = true) }
        return when {
            segments.size >= 2 && (segments[0] == "u" || segments[0] == "i") -> segments[1]
            segments.size == 1 -> segments[0]
            else -> null
        }
    }

    private suspend fun cleanUpLegacyAddon(currentTvBaseUrl: String) {
        try {
            val installed = addonRepository.getInstalledAddons().first()
            for (addon in installed) {
                val isCollections = addon.id.equals(MyListsDefaultSeed.MY_LISTS_COLLECTIONS_ADDON_ID, ignoreCase = true)
                if (isCollections) continue
                val isLegacyId = addon.id.equals(MyListsDefaultSeed.MY_LISTS_ADDON_ID, ignoreCase = true)
                val isOldUrl = MyListsDefaultSeed.isMyListsAddonUrl(addon.baseUrl) &&
                    !addon.baseUrl.equals(currentTvBaseUrl, ignoreCase = true)
                if (isLegacyId || isOldUrl) {
                    Log.i(TAG, "Removing legacy/duplicate My Lists addon: id=${addon.id}, baseUrl=${addon.baseUrl}")
                    addonRepository.removeAddon(addon.baseUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed cleaning up legacy My Lists addon: ${e.message}")
        }
    }

    /** What asking the sync endpoint gave: not modified, the payload (with its tag), or nothing usable. */
    private class SyncFetch(val payload: MyListsSyncResponse?, val etag: String?, val notModified: Boolean = false)

    /** The tag of the payload last applied, per profile and target. In memory: a restart downloads it once. */
    private val syncEtags = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun syncEtagKey(profileId: Int, targetId: String) = "$profileId|$targetId"

    private fun fetchSyncPayload(targetId: String, ifNoneMatch: String?): SyncFetch {
        val encodedTarget = URLEncoder.encode(targetId, "UTF-8").replace("+", "%20")
        val url = "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/api/nuvio-tv/sync/$encodedTarget"
        val request = Request.Builder()
            .url(url)
            .header("Origin", MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN)
            .header("Referer", "${MyListsDefaultSeed.MY_LISTS_BASE_ORIGIN}/")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (!ifNoneMatch.isNullOrBlank()) header("If-None-Match", ifNoneMatch) }
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 304) return SyncFetch(payload = null, etag = ifNoneMatch, notModified = true)
                if (!response.isSuccessful) {
                    Log.d(TAG, "Sync endpoint returned HTTP ${response.code} for url=$url")
                    return SyncFetch(null, null)
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return SyncFetch(null, null)
                val parsed = gson.fromJson(body, MyListsSyncResponse::class.java)
                if (parsed?.ok == true) SyncFetch(parsed, response.header("ETag")) else SyncFetch(null, null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to reach My Lists sync endpoint: ${e.message}")
            SyncFetch(null, null)
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
        val profileId = profileManager.activeProfileId.value
        store(profileId).edit { prefs ->
            prefs[manifestUrlKey] = cleanNew
        }
        val active = profileManager.activeProfile
        if (active == null || active.isPrimary || !active.usesPrimaryAddons) {
            val currentUrls = addonPreferences.installedAddonUrls.first()
            val existingMyListsIndex = currentUrls.indexOfFirst { MyListsDefaultSeed.isMyListsAddonUrl(it) }
            val nonMyListsUrls = currentUrls.filterNot { MyListsDefaultSeed.isMyListsAddonUrl(it) }
            val insertIndex = if (existingMyListsIndex >= 0) existingMyListsIndex.coerceAtMost(nonMyListsUrls.size) else 0
            val updatedUrls = nonMyListsUrls.toMutableList()
            updatedUrls.add(insertIndex, cleanNew)
            addonPreferences.setAddonOrder(updatedUrls.distinct())
        }
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
        val allCollections = collectionsDataStore.getCurrentCollections()
        val collectionCatalogKeys = MyListsDefaultSeed.extractCollectionCatalogKeys(allCollections, addon.id)

        val isCustom = !MyListsDefaultSeed.isMyListsDefaultUrl(addon.baseUrl)
        val homeCatalogs = if (syncCatalogOrder != null && isCustom) {
            val allowedIds = syncCatalogOrder.toSet()
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .filter { it.id in allowedIds }
                .filterNot { MyListsDefaultSeed.isCollectionCatalogSource(allCollections, addon.id, it.apiType, it.id) }
        } else {
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .filterNot { MyListsDefaultSeed.isCollectionCatalogSource(allCollections, addon.id, it.apiType, it.id) }
        }
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
        val defaultSeedKeys = MyListsDefaultSeed.DEFAULT_HOME_CATALOG_KEYS.toSet()
        val removedCatalogKeys = (
            previousKnownCatalogKeys.filter { it !in newSet } +
                extraRemovedCatalogKeys +
                (if (isCustom) defaultSeedKeys.filter { it !in newSet } else emptyList())
        ).toSet()

        // Auto-enable any newly added My Lists catalogs and ensure removed keys are disabled
        val currentDisabled = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toMutableSet()
        val disabledRemoved = currentDisabled.removeAll(addedCatalogKeys.toSet())
        val disabledAdded = currentDisabled.addAll(removedCatalogKeys + collectionCatalogKeys)
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
                key in collectionCatalogKeys ||
                key.startsWith("${MyListsDefaultSeed.MY_LISTS_COLLECTIONS_ADDON_ID}_") ||
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
