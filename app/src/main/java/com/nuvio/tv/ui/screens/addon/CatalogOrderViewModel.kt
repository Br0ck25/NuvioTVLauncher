package com.nuvio.tv.ui.screens.addon

import android.content.Context
import androidx.lifecycle.ViewModel
import com.nuvio.tv.R
import com.nuvio.tv.core.apps.INSTALLED_APPS_CATALOG_KEY
import com.nuvio.tv.core.apps.isIndependentHomeCatalogKey
import com.nuvio.tv.core.apps.mergeHomeCatalogOrder
import com.nuvio.tv.core.apps.moveIndependentHomeCatalog
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val serverCatalog: ServerCatalog
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveCollectionBetweenAddons(key, -1)
        } else {
            moveCatalog(key, -1)
        }
    }

    fun moveDown(key: String) {
        if (_uiState.value.followAddonsOrder) {
            moveCollectionBetweenAddons(key, 1)
        } else {
            moveCatalog(key, 1)
        }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * When followAddonsOrder is enabled, collections and Installed Apps jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveCollectionBetweenAddons(key: String, direction: Int) {
        if (!isIndependentHomeCatalogKey(key)) return

        val items = _uiState.value.items
        val currentIndex = items.indexOfFirst { it.key == key }
        if (currentIndex == -1) return

        val reordered = moveIndependentHomeCatalog(
            order = items.map { it.key },
            key = key,
            direction = direction,
            addonOwner = { rowKey -> items.firstOrNull { it.key == rowKey }?.addonName }
        )

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                combine(addonRepository.getInstalledAddons(), serverCatalog.addons) { installed, servers ->
                    installed.enabledAddons() + servers
                },
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean

                val items = buildOrderedCatalogItems(
                    addons = addons,
                    collections = collections,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons
                )
                Pair(items, followAddons)
            }.collectLatest { (orderedItems, followAddons) ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        followAddonsOrder = followAddons
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String> = emptyMap(),
        followAddonsOrder: Boolean = false
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons, collections)
        val collectionEntries = collections.map { collection ->
            CatalogOrderEntry(
                key = "collection_${collection.id}",
                disableKey = "collection_${collection.id}",
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                typeLabel = "collection"
            )
        }
        val installedAppsEntry = CatalogOrderEntry(
            key = INSTALLED_APPS_CATALOG_KEY,
            disableKey = INSTALLED_APPS_CATALOG_KEY,
            catalogName = context.getString(R.string.installed_apps_title),
            addonName = context.getString(R.string.installed_apps_catalog_subtitle),
            typeLabel = "apps"
        )
        // Premade and user collections lead the list by default, followed by addon catalogs and Installed Apps.
        val allEntries = collectionEntries + defaultEntries + listOf(installedAppsEntry)
        val availableMap = allEntries.associateBy { it.key }
        val defaultOrderKeys = allEntries.map { it.key }

        val effectiveOrder = mergeHomeCatalogOrder(
            addonKeys = defaultEntries.map { it.key },
            independentKeys = collectionEntries.map { it.key } + INSTALLED_APPS_CATALOG_KEY,
            savedOrderKeys = savedOrderKeys,
            followAddonsOrder = followAddonsOrder,
            addonOwner = { availableMap[it]?.addonName }
        )

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            val isIndependent = isIndependentHomeCatalogKey(key)

            val canMoveUp: Boolean
            val canMoveDown: Boolean
            if (followAddonsOrder) {
                if (isIndependent) {
                    canMoveUp = index > 0
                    canMoveDown = index < effectiveOrder.lastIndex
                } else {
                    canMoveUp = false
                    canMoveDown = false
                }
            } else {
                canMoveUp = index > 0
                canMoveDown = index < effectiveOrder.lastIndex
            }

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown
            )
        }
    }

    private fun buildDefaultCatalogEntries(
        addons: List<Addon>,
        collections: List<Collection> = emptyList()
    ): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            if (addon.id.equals(com.nuvio.tv.core.mylists.MyListsDefaultSeed.MY_LISTS_COLLECTIONS_ADDON_ID, ignoreCase = true)) {
                return@forEach
            }
            addon.catalogs
                .filterNot {
                    it.isSearchOnlyCatalog() ||
                    com.nuvio.tv.core.mylists.MyListsDefaultSeed.isRemovedCatalog(it.id, it.apiType) ||
                    com.nuvio.tv.core.mylists.MyListsDefaultSeed.isCollectionCatalogSource(collections, addon.id, it.apiType, it.id)
                }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return homeCatalogKey(addonId, type, catalogId)
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val legacyDisableKey: String? = null,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
