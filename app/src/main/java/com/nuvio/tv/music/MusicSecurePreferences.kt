package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log

/**
 * Small encrypted-preferences helper for the Music section.
 *
 * Addon URLs can carry a private token in their path and the Last.fm session key grants access
 * to an account, so neither should sit in a plain XML file. The keyset is wrapped by a key in
 * Android Keystore; if this device cannot provide one, the caller gets the plain file instead of
 * a crash. Existing plain values are copied in and the old file is removed only after every value
 * reads back from the encrypted store.
 */
internal object MusicSecurePreferences {
    private const val TAG = "MusicSecurePrefs"
    private val opened = HashMap<String, SharedPreferences>()

    /**
     * The first profile keeps the original files; every other profile has its own (names get "_p<id>"). A profile
     * that already existed when sign-ins became per profile starts with a copy of the first profile's values.
     */
    @Synchronized
    fun open(
        context: Context,
        encryptedName: String,
        plainName: String,
        legacyName: String? = null
    ): SharedPreferences {
        val profile = com.nuvio.tv.core.profile.ActiveProfile.id
        if (profile <= 1) return openFiles(context, encryptedName, plainName, legacyName)
        val mine = openFiles(
            context,
            com.nuvio.tv.core.profile.ActiveProfile.prefsName(encryptedName, profile),
            com.nuvio.tv.core.profile.ActiveProfile.prefsName(plainName, profile),
            null
        )
        if (!mine.getBoolean(COPIED, false)) {
            if (!com.nuvio.tv.core.profile.ActiveProfile.baselineReady.isCompleted) {
                runCatching {
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { com.nuvio.tv.core.profile.ActiveProfile.baselineReady.await() } }
                }
            }
            val edit = mine.edit().putBoolean(COPIED, true)
            if (com.nuvio.tv.core.profile.ActiveProfile.isLegacy(profile)) {
                openFiles(context, encryptedName, plainName, legacyName).all.forEach { (k, v) ->
                    when (v) {
                        is String -> edit.putString(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is Int -> edit.putInt(k, v)
                        is Long -> edit.putLong(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                }
            }
            edit.apply()
        }
        return mine
    }

    private const val COPIED = "__copied_from_first_profile"

    private fun openFiles(
        context: Context,
        encryptedName: String,
        plainName: String,
        legacyName: String?
    ): SharedPreferences {
        opened[encryptedName]?.let { return it }
        val app = context.applicationContext
        val store = runCatching { create(app, encryptedName) }
            .recoverCatching {
                // A restored keyset cannot be unwrapped by this device's Keystore. The file is
                // unreadable either way, so recreate it rather than falling back forever.
                app.deleteSharedPreferences(encryptedName)
                create(app, encryptedName)
            }
            .getOrElse { error ->
                Log.w(TAG, "Keystore-backed prefs unavailable for $encryptedName; using plain fallback", error)
                val fallback = app.getSharedPreferences(plainName, Context.MODE_PRIVATE)
                opened[encryptedName] = fallback
                return fallback
            }

        if (legacyName != null) {
            moveIn(app, store, legacyName)
        }
        moveIn(app, store, plainName)
        opened[encryptedName] = store
        return store
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun moveIn(context: Context, target: SharedPreferences, sourceName: String) {
        val source = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
        val entries = source.all
        if (entries.isEmpty()) {
            context.deleteSharedPreferences(sourceName)
            return
        }
        val editor = target.edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!editor.commit()) {
            Log.w(TAG, "Could not migrate $sourceName into encrypted storage")
            return
        }
        val stored = target.all
        if (entries.any { (key, value) -> stored[key] != value }) {
            Log.w(TAG, "Migration of $sourceName didn't read back completely; keeping plain copy")
            return
        }
        context.deleteSharedPreferences(sourceName)
    }
}
