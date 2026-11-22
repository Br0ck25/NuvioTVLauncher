package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.diagnostics.DebugReportBuilder
import com.nuvio.tv.core.diagnostics.DebugReportUploader
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DebugReportUiState {
    data object Idle : DebugReportUiState
    data object Sending : DebugReportUiState
    data class Sent(val reportId: String) : DebugReportUiState
    data class Failed(val message: String) : DebugReportUiState
}

@HiltViewModel
class DebugReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val authManager: AuthManager
) : ViewModel() {

    private val _state = MutableStateFlow<DebugReportUiState>(DebugReportUiState.Idle)
    val state = _state.asStateFlow()

    fun send() {
        if (_state.value is DebugReportUiState.Sending) return
        _state.value = DebugReportUiState.Sending
        viewModelScope.launch {
            val result = runCatching {
                val addons = addonRepository.getInstalledAddons().first()
                val diagnostics = playerSettingsDataStore.lastPlaybackDiagnostics.first()
                val settings = playerSettingsDataStore.playerSettings.first()
                val report = DebugReportBuilder.build(
                    context = context,
                    addons = addons,
                    diagnostics = diagnostics,
                    playerPreference = settings.playerPreference.name,
                    internalPlayerEngine = settings.internalPlayerEngine.name,
                    authState = authManager.authState.value
                )
                DebugReportUploader.upload(report)
                report.id
            }
            _state.value = result.fold(
                onSuccess = { DebugReportUiState.Sent(it) },
                onFailure = {
                    DebugReportUiState.Failed(it.message?.take(200) ?: "Unknown error")
                }
            )
        }
    }

    fun reset() {
        if (_state.value !is DebugReportUiState.Sending) {
            _state.value = DebugReportUiState.Idle
        }
    }
}
