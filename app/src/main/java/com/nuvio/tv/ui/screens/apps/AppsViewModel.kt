package com.nuvio.tv.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.apps.InstalledAppsRepository
import com.nuvio.tv.core.apps.InstalledAppsState
import com.nuvio.tv.core.apps.orderInstalledApps
import com.nuvio.tv.data.local.InstalledAppsPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository,
    private val preferencesDataStore: InstalledAppsPreferencesDataStore
) : ViewModel() {
    val state: StateFlow<InstalledAppsState> = combine(
        repository.state,
        preferencesDataStore.preferences
    ) { repoState, prefs ->
        val byPackage = repoState.apps.associateBy { it.packageName }
        val ordered = orderInstalledApps(
            repoState.apps.map { it.packageName },
            prefs.order,
            emptySet()
        ).mapNotNull(byPackage::get)
        InstalledAppsState(
            apps = ordered,
            order = prefs.order,
            isLoading = repoState.isLoading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstalledAppsState())

    fun refresh() {
        repository.refresh()
    }
}

