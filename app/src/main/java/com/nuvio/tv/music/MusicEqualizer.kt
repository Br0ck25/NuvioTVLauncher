package com.nuvio.tv.music

import com.nuvio.tv.core.audio.SevenBandEqualizerProfile
import com.nuvio.tv.core.audio.SevenBandEqualizerSettings
import com.nuvio.tv.core.audio.EqualizerOwner
import com.nuvio.tv.ui.screens.player.SevenBandEqualizerAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Music keeps its own seven-band profile and audio processors, separate from video playback. */
object MusicEqualizer {
    internal val audioProcessor = SevenBandEqualizerAudioProcessor()
    internal val videoAudioProcessor = SevenBandEqualizerAudioProcessor()

    private val _available = MutableStateFlow(true)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    fun apply(profile: SevenBandEqualizerProfile = SevenBandEqualizerSettings.current(EqualizerOwner.MUSIC)) {
        val gains = if (profile.isEnabled) profile.gainsDb else List(7) { 0 }
        audioProcessor.setGainsDb(gains)
        videoAudioProcessor.setGainsDb(gains)
    }

    internal fun release() {
        audioProcessor.setGainsDb(List(7) { 0 })
        videoAudioProcessor.setGainsDb(List(7) { 0 })
    }
}
