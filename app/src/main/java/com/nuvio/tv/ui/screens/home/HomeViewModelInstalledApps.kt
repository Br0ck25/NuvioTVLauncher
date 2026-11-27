package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Launcher: keeps the Apps row's content (the installed apps, in the user's order and names) up to date.
 * Called from `HomeViewModel`'s init; kept here so the Nuvio One pipeline file stays as upstream has it.
 */
internal fun HomeViewModel.observeInstalledAppsRowPipeline() {
    val repository = installedAppsRepository ?: return
    viewModelScope.launch {
        repository.homeApps
            .distinctUntilChanged()
            .collectLatest { apps ->
                installedAppsCache = apps
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
    }
}
