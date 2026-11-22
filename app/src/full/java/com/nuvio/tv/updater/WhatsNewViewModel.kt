package com.nuvio.tv.updater

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows the notes of the installed release once, the first time the app opens after an update.
 * Nothing is shown (and nothing is marked as seen) when the notes can't be fetched, so it tries again next launch.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val updatePreferences: UpdatePreferences
) : ViewModel() {

    private val _notes = MutableStateFlow<AppUpdate?>(null)
    val notes: StateFlow<AppUpdate?> = _notes.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val owner = BuildConfig.GITHUB_OWNER
        val repo = BuildConfig.GITHUB_REPO
        if (owner.isBlank() || repo.isBlank()) return
        val current = BuildConfig.VERSION_NAME
        if (updatePreferences.whatsNewSeenVersion.first() == current) return
        val release = runCatching {
            // Releases are tagged with the plain version, but a leading "v" is accepted too.
            listOf(current, "v$current").firstNotNullOfOrNull { tag ->
                gitHubReleaseApi.getReleaseByTag(owner, repo, tag).takeIf { it.isSuccessful }?.body()
            }
        }.onFailure { Log.w(TAG, "couldn't load release notes", it) }.getOrNull() ?: return
        val body = release.body.orEmpty().trim()
        if (body.isBlank()) {
            updatePreferences.setWhatsNewSeenVersion(current)
            return
        }
        _notes.value = AppUpdate(
            tag = release.tagName ?: current,
            title = release.name?.takeIf { it.isNotBlank() } ?: current,
            notes = body,
            releaseUrl = release.htmlUrl,
            assetName = "",
            assetUrl = "",
            assetSizeBytes = null
        )
    }

    fun dismiss() {
        _notes.value = null
        viewModelScope.launch { updatePreferences.setWhatsNewSeenVersion(BuildConfig.VERSION_NAME) }
    }

    private companion object {
        const val TAG = "WhatsNew"
    }
}
