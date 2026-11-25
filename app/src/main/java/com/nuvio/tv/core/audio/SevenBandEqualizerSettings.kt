package com.nuvio.tv.core.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EqualizerOwner(val preferencePrefix: String) {
    PLAYBACK("playback"),
    MUSIC("music")
}

enum class SevenBandPreset(val label: String, val gainsDb: List<Int>) {
    OFF("Off", List(7) { 0 }),
    FLAT("Flat", List(7) { 0 }),
    BASS("Bass", listOf(5, 4, 2, 0, 0, 0, 0)),
    VOCAL("Vocal", listOf(-2, -1, 0, 3, 3, 1, 0)),
    TREBLE("Treble", listOf(0, 0, -1, 0, 2, 4, 4)),
    MOVIE("Movie", listOf(2, 1, 0, 2, 3, 1, -1)),
    ROCK("Rock", listOf(4, 2, -1, 2, 3, 2, 1)),
    CUSTOM("Custom", List(7) { 0 });

    companion object {
        val selectable: List<SevenBandPreset> = entries.toList()
    }
}

data class SevenBandEqualizerProfile(
    val preset: SevenBandPreset = SevenBandPreset.OFF,
    val gainsDb: List<Int> = SevenBandPreset.OFF.gainsDb
) {
    val isEnabled: Boolean get() = preset != SevenBandPreset.OFF && gainsDb.any { it != 0 }

    companion object {
        val DEFAULT = SevenBandEqualizerProfile()
    }
}

/** Independent profile storage for general playback and Music. */
object SevenBandEqualizerSettings {
    const val BAND_COUNT = 7
    val frequenciesHz = listOf(125, 250, 500, 1_000, 2_000, 4_000, 8_000)

    private const val PREFERENCES_NAME = "nuvio_seven_band_equalizer"
    private var preferences: SharedPreferences? = null
    private val profiles = EqualizerOwner.entries.associateWith {
        MutableStateFlow(SevenBandEqualizerProfile.DEFAULT)
    }

    @Synchronized
    fun init(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        EqualizerOwner.entries.forEach { owner ->
            val prefix = owner.preferencePrefix
            val preset = runCatching {
                SevenBandPreset.valueOf(prefs.getString("${prefix}_preset", null).orEmpty())
            }.getOrDefault(SevenBandPreset.OFF)
            val savedGains = prefs.getString("${prefix}_bands", null)
                ?.split(',')
                ?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.size == BAND_COUNT }
                ?.map { it.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
            val gains = savedGains ?: preset.gainsDb
            profiles.getValue(owner).value = SevenBandEqualizerProfile(preset, gains)
        }
    }

    fun profile(owner: EqualizerOwner): StateFlow<SevenBandEqualizerProfile> =
        profiles.getValue(owner).asStateFlow()

    fun current(owner: EqualizerOwner): SevenBandEqualizerProfile = profiles.getValue(owner).value

    fun hasSavedProfile(owner: EqualizerOwner): Boolean {
        val prefix = owner.preferencePrefix
        val prefs = preferences ?: return false
        return prefs.contains("${prefix}_preset") || prefs.contains("${prefix}_bands")
    }

    fun selectPreset(owner: EqualizerOwner, preset: SevenBandPreset) {
        val gains = if (preset == SevenBandPreset.CUSTOM) current(owner).gainsDb else preset.gainsDb
        val profile = SevenBandEqualizerProfile(preset, gains)
        save(owner, profile)
    }

    fun setBand(owner: EqualizerOwner, index: Int, gainDb: Int) {
        if (index !in 0 until BAND_COUNT) return
        val current = current(owner)
        val next = current.gainsDb.toMutableList().also {
            it[index] = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        val preset = if (next.all { it == 0 }) SevenBandPreset.FLAT else SevenBandPreset.CUSTOM
        save(owner, SevenBandEqualizerProfile(preset, next))
    }

    fun reset(owner: EqualizerOwner) = selectPreset(owner, SevenBandPreset.FLAT)

    private fun save(owner: EqualizerOwner, profile: SevenBandEqualizerProfile) {
        profiles.getValue(owner).value = profile
        val prefix = owner.preferencePrefix
        preferences?.edit()
            ?.putString("${prefix}_preset", profile.preset.name)
            ?.putString("${prefix}_bands", profile.gainsDb.joinToString(","))
            ?.apply()
    }

    const val MIN_GAIN_DB = -12
    const val MAX_GAIN_DB = 12
}
