package com.nuvio.tv.core.apps

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shared by the Apps page and the Home row. */
@HiltViewModel
class InstalledAppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository
) : ViewModel() {
    val apps: StateFlow<List<InstalledApp>> = repository.apps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun image(app: InstalledApp): Bitmap? = repository.image(app)
    fun launch(app: InstalledApp) = repository.launch(app)
    fun move(app: InstalledApp, delta: Int) = repository.move(apps.value, app, delta)
    fun openAppInfo(app: InstalledApp) = repository.openAppInfo(app)
    fun rename(app: InstalledApp, name: String?) = repository.rename(app, name)
}
