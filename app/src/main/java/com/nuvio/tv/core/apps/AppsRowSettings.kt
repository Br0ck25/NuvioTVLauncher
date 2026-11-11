package com.nuvio.tv.core.apps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the Apps row sits among the Home rows. */
enum class AppsRowPosition(val id: String) {
    /** Above everything else on Home. */
    TOP("top"),

    /** Under Continue Watching (the default). */
    UNDER_CONTINUE_WATCHING("under_cw"),

    /** After all the other rows. */
    BOTTOM("bottom");

    /** The next choice, wrapping round: what a "change" button steps to. */
    fun next(): AppsRowPosition = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): AppsRowPosition = entries.firstOrNull { it.id == id } ?: UNDER_CONTINUE_WATCHING
    }
}

/** The saved choice, shared by the Home layouts and the settings page. */
object AppsRowSettings {
    private const val FILE = "launcher_apps"
    private const val KEY = "row_position"
    private val state = MutableStateFlow(AppsRowPosition.UNDER_CONTINUE_WATCHING)
    @Volatile private var loaded = false

    fun position(context: Context): StateFlow<AppsRowPosition> {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    state.value = AppsRowPosition.fromId(
                        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull()
                    )
                    loaded = true
                }
            }
        }
        return state.asStateFlow()
    }

    fun set(context: Context, position: AppsRowPosition) {
        position(context)
        runCatching { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, position.id).apply() }
        state.value = position
    }
}
