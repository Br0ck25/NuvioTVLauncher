package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.data.tracking.PersonalRatingsService
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.components.NuvioDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The title identity sent to the tracking services, or null when it has no resolvable id. */
internal fun personalRatingTarget(meta: Meta): TrackingMediaReference? {
    val base = buildTrackingMediaReference(
        contentType = meta.rawType,
        parentMetaId = meta.id,
        title = meta.name,
        releaseInfo = meta.releaseInfo
    )
    val ids = if (base.ids.imdb == null && meta.imdbId != null) {
        base.ids.copy(imdb = meta.imdbId.takeIf { it.startsWith("tt", ignoreCase = true) })
    } else {
        base.ids
    }
    return base.copy(ids = ids).takeIf { it.ids.hasAny }
}

@HiltViewModel
internal class PersonalRatingsViewModel @Inject constructor(
    private val service: PersonalRatingsService,
    traktSettings: com.nuvio.tv.data.local.TraktSettingsDataStore
) : ViewModel() {
    /** Whether to ask for a rating when a movie finishes; off unless the user turns it on. */
    val promptEnabled: StateFlow<Boolean> = traktSettings.promptToRateAfterWatching
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    data class UiState(
        val open: Boolean = false,
        val busy: Boolean = false,
        val rating: Int? = null,
        val results: List<PersonalRatingsService.Outcome> = emptyList(),
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var target: TrackingMediaReference? = null
    private var job: Job? = null

    fun connected(): List<TrackingProviderId> = service.connected()

    fun open(media: TrackingMediaReference) {
        job?.cancel()
        target = media
        _state.value = UiState(open = true)
    }

    fun submit(rating: Int?) {
        val media = target ?: return
        if (_state.value.busy) return
        job?.cancel()
        _state.value = _state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                val results = service.submit(media, rating)
                _state.value = _state.value.copy(busy = false, rating = rating, results = results)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "Failed")
            }
        }
    }

    fun close() {
        job?.cancel()
        target = null
        _state.value = UiState()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PersonalRatingDialog(
    title: String,
    state: PersonalRatingsViewModel.UiState,
    connected: List<TrackingProviderId>,
    onSubmit: (Int?) -> Unit,
    onClose: () -> Unit
) {
    if (!state.open) return
    NuvioDialog(onDismiss = onClose, title = "Your rating", subtitle = title, width = 560.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Half-star steps · 1–10 on your trackers", style = MaterialTheme.typography.bodyMedium)
            if (state.busy) Text("Saving…", style = MaterialTheme.typography.bodyMedium)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            for (result in state.results) {
                val status = when {
                    !result.ok -> "failed" + (result.message?.let { " — $it" } ?: "")
                    state.rating == null -> "rating removed"
                    else -> "${state.rating}/10"
                }
                Text("${result.provider.name.lowercase()}: $status", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            for (start in listOf(1, 6)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (rating in start until start + 5) {
                        Button(
                            onClick = { onSubmit(rating) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) { Text("%.1f★".format(rating / 2.0), maxLines = 1, softWrap = false) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Submit to: " + connected.joinToString { it.name.lowercase() }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onSubmit(null) }, enabled = !state.busy) { Text("Remove rating") }
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}
