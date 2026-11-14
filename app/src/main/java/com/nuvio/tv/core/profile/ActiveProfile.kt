package com.nuvio.tv.core.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * The Nuvio profile in use, for code that has only a Context. Everything a profile can change is kept per profile: the
 * first profile keeps the original files, every other profile has its own copy named with "_p<id>" on the end.
 */
object ActiveProfile {
    @Volatile var id: Int = 1

    fun prefsName(base: String, profileId: Int = id) = if (profileId <= 1) base else "${base}_p$profileId"

    /** The preferences file [base] of the profile in use. Read it each time rather than keeping it, so a profile switch is followed. */
    fun prefs(context: Context, base: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName(base), Context.MODE_PRIVATE)
}
