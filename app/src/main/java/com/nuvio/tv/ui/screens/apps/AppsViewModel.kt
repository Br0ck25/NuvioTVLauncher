package com.nuvio.tv.ui.screens.apps

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.apps.InstalledAppsRepository
import com.nuvio.tv.core.apps.InstalledAppsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository
) : ViewModel() {
    val state: StateFlow<InstalledAppsState> = repository.state

    fun refresh() {
        repository.refresh()
    }
}
