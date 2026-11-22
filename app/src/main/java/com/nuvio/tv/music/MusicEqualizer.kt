package com.nuvio.tv.music

import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A five-band style equalizer attached to the music player's audio session.
 *
 * The device decides how many bands it has, so each preset is expressed as a curve over the
 * band centre frequencies rather than as fixed band indices. If the TV has no equalizer effect
 * the preset is still remembered, and it is applied if the effect becomes available later.
 */
object MusicEqualizer {
    private const val TAG = "MusicEqualizer"

    private var effect: Equalizer? = null
    private var sessionId: Int = 0
    private var preset: MusicEqualizerPreset = MusicEqualizerPreset.OFF

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (effect != null && sessionId == audioSessionId) return
        release()
        sessionId = audioSessionId
        runCatching { Equalizer(0, audioSessionId) }
            .onSuccess { equalizer ->
                effect = equalizer
                _available.value = true
                apply(preset)
            }
            .onFailure { error ->
                Log.w(TAG, "Equalizer unavailable for session $audioSessionId", error)
                _available.value = false
            }
    }

    @Synchronized
    fun apply(newPreset: MusicEqualizerPreset) {
        preset = newPreset
        val equalizer = effect ?: return
        runCatching {
            if (newPreset == MusicEqualizerPreset.OFF) {
                equalizer.enabled = false
                return
            }
            equalizer.enabled = true
            val range = equalizer.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            for (band in 0 until equalizer.numberOfBands) {
                val frequencyHz = equalizer.getCenterFreq(band.toShort()) / 1000
                val target = when (newPreset) {
                    MusicEqualizerPreset.FLAT -> 0
                    MusicEqualizerPreset.BASS -> when {
                        frequencyHz < 250 -> max * 0.6f
                        frequencyHz < 500 -> max * 0.25f
                        else -> 0f
                    }
                    MusicEqualizerPreset.VOCAL -> when {
                        frequencyHz in 250..4_000 -> max * 0.4f
                        else -> 0f
                    }
                    MusicEqualizerPreset.ROCK -> when {
                        frequencyHz < 250 -> max * 0.45f
                        frequencyHz <= 2_000 -> -max * 0.15f
                        else -> max * 0.45f
                    }
                    MusicEqualizerPreset.OFF -> 0f
                }
                equalizer.setBandLevel(
                    band.toShort(),
                    target.toInt().coerceIn(min, max).toShort()
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not apply equalizer preset $newPreset", error)
        }
    }

    @Synchronized
    fun release() {
        runCatching { effect?.release() }
        effect = null
        sessionId = 0
        _available.value = false
    }
}
