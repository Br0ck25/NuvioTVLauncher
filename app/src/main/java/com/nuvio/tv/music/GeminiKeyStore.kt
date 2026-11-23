package com.nuvio.tv.music

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Gemini API key for each profile, encrypted with the Android Keystore.
 *
 * The key rides the same per-profile provider-credential sync as Debrid/MDBList keys, so a
 * listener does not have to type it again on another TV. It is never part of the normal settings
 * blob, never logged and never shown again after saving.
 */
@Singleton
class GeminiKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : ProfileScopedCredentialStore {
    private val prefs = ProtectedProfilePreferences(
        context.applicationContext,
        "nuvio_music_gemini_secure",
        "nuvio_music_gemini_key"
    )

    private val _revision = MutableStateFlow(0L)

    /** Changes whenever this profile's key is edited locally, so credential sync can push it. */
    val revision: StateFlow<Long> = _revision

    fun read(profileId: Int): String? = prefs.read(profileId)?.takeIf { it.isNotBlank() }

    fun write(profileId: Int, key: String?, notify: Boolean = true) {
        prefs.write(profileId, key?.trim()?.takeIf { it.isNotBlank() })
        if (notify) _revision.value += 1
    }

    override fun removeProfile(profileId: Int) {
        write(profileId, null, notify = false)
    }

    override fun clearAllProfiles() {
        prefs.clear()
        _revision.value += 1
    }
}
