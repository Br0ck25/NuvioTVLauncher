package com.nuvio.tv.ui.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.SectionScale
import com.nuvio.tv.domain.model.SectionScales
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live TV fork: the active profile's per-section text and interface sizes.
 *
 * The first value is the same size the app used before this setting existed, so there is no
 * flash or resize while the stored values are read.
 */
@HiltViewModel
class SectionScalingViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {
    val scales: StateFlow<Map<String, SectionScale>> = layoutPreferenceDataStore.sectionScales
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SectionScales.defaults()
        )

    fun setTextScale(section: String, percent: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setSectionTextScale(section, percent)
        }
    }

    fun setUiScale(section: String, percent: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setSectionUiScale(section, percent)
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            layoutPreferenceDataStore.resetSectionScales()
        }
    }
}
