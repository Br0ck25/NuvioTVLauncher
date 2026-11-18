package com.nuvio.tv.core.profile

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred

/**
 * The Nuvio profile in use, for code that has only a Context. Everything a profile can change is kept per profile: the
 * first profile keeps the original files, every other profile has its own copy named with "_p<id>" on the end.
 *
 * A profile that already existed when per-profile settings arrived keeps what it saw until then: its files start as a
 * one-time copy of the first profile's ([isLegacy]). Every profile created after that starts empty. Nothing is copied
 * between profiles again after that.
 */
object ActiveProfile {
    /** Switching profile also forgets the Home backdrop shown last, so one profile never sees another's artwork. */
    @Volatile var id: Int = 1
        set(value) {
            if (field != value) com.nuvio.tv.ui.screens.home.HeroBackdropState.reset()
            field = value
        }

    /** Completes once the profiles that existed before per-profile settings are known. */
    val baselineReady = CompletableDeferred<Unit>()
    @Volatile private var legacy: Set<Int> = emptySet()

    fun isLegacy(profileId: Int) = profileId in legacy

    /** Called once the stored profile list is read: the first time ever, the profiles then present are the legacy ones. */
    fun initBaseline(context: Context, existingIds: List<Int>) {
        val bp = context.applicationContext.getSharedPreferences(BASELINE, Context.MODE_PRIVATE)
        if (!bp.getBoolean(DONE, false)) {
            bp.edit().putStringSet(LEGACY, existingIds.filter { it > 1 }.map { it.toString() }.toSet()).putBoolean(DONE, true).apply()
        }
        legacy = bp.getStringSet(LEGACY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
        baselineReady.complete(Unit)
    }

    fun prefsName(base: String, profileId: Int = id) = if (profileId <= 1) base else "${base}_p$profileId"

    /** The preferences file [base] of [profileId]. A legacy profile's file is filled from the first profile's the first time. */
    fun prefsFor(context: Context, base: String, profileId: Int): SharedPreferences {
        val app = context.applicationContext
        val mine = app.getSharedPreferences(prefsName(base, profileId), Context.MODE_PRIVATE)
        if (profileId > 1 && isLegacy(profileId)) {
            val bp = app.getSharedPreferences(BASELINE, Context.MODE_PRIVATE)
            val flag = "copied_${base}_$profileId"
            if (!bp.getBoolean(flag, false)) {
                val from = app.getSharedPreferences(base, Context.MODE_PRIVATE).all
                val edit = mine.edit()
                for ((k, v) in from) {
                    if (mine.contains(k)) continue
                    when (v) {
                        is Boolean -> edit.putBoolean(k, v)
                        is Int -> edit.putInt(k, v)
                        is Long -> edit.putLong(k, v)
                        is Float -> edit.putFloat(k, v)
                        is String -> edit.putString(k, v)
                        is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                }
                edit.apply()
                bp.edit().putBoolean(flag, true).apply()
            }
        }
        return mine
    }

    /** The preferences file [base] of the profile in use. Read it each time rather than keeping it, so a profile switch is followed. */
    fun prefs(context: Context, base: String): SharedPreferences = prefsFor(context, base, id)

    private const val BASELINE = "profile_baseline"
    private const val DONE = "done"
    private const val LEGACY = "legacy"
}
