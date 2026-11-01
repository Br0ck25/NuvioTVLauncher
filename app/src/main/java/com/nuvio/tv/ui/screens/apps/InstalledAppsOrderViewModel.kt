package com.nuvio.tv.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.apps.InstalledApp
import com.nuvio.tv.core.apps.InstalledAppsRepository
import com.nuvio.tv.core.apps.orderInstalledApps
import com.nuvio.tv.data.local.InstalledAppsPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InstalledAppsOrderUiState(
    val isLoading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val removedApps: List<InstalledApp> = emptyList()
)

@HiltViewModel
class InstalledAppsOrderViewModel @Inject constructor(
    private val repository: InstalledAppsRepository,
    private val preferences: InstalledAppsPreferencesDataStore
) : ViewModel() {
    val uiState = combine(repository.state, preferences.preferences) { state, prefs ->
        val byPackage = state.apps.associateBy { it.packageName }
        InstalledAppsOrderUiState(
            isLoading = state.isLoading,
            apps = orderInstalledApps(state.apps.map { it.packageName }, prefs.order, prefs.removedPackages)
                .mapNotNull(byPackage::get),
            removedApps = state.apps.filter { it.packageName in prefs.removedPackages }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstalledAppsOrderUiState())

    init { repository.refresh() }

    fun move(packageName: String, direction: Int) = update { packages ->
        preferences.moveApp(packageName, direction, packages)
    }

    fun remove(packageName: String) = update { packages -> preferences.removeFromRow(packageName, packages) }

    fun restore(packageName: String) = update { packages -> preferences.restoreToRow(packageName, packages) }

    fun restoreAll() = update(preferences::restoreAll)

    private fun update(block: suspend (List<String>) -> Unit) {
        val packages = repository.state.value.apps.map { it.packageName }
        viewModelScope.launch { block(packages) }
    }
}
