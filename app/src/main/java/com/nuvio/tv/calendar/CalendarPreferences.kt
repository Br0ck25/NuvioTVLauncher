package com.nuvio.tv.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.calendarDataStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_preferences")

data class CalendarSettings(
    val calendarInMenu: Boolean = true,
    val calendarOff: Boolean = false,
    val calendarUpcomingRow: Boolean = true,
    val calendarNewEpisodeCards: Boolean = true,
    val calendarHideSpoilers: Boolean = true,
    val calendarCountry: String = "US",
    val calendarSkipNewsTalk: Boolean = true,
    val calendarView: String = "MONTH",
    val calendarHideWatched: Boolean = false,
    val use24HourClock: Boolean = false
)

@Singleton
class CalendarPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val store = context.calendarDataStore

    private object Keys {
        val calendarInMenu = booleanPreferencesKey("calendar_in_menu")
        val calendarOff = booleanPreferencesKey("calendar_off")
        val calendarUpcomingRow = booleanPreferencesKey("calendar_upcoming_row")
        val calendarNewEpisodeCards = booleanPreferencesKey("calendar_new_episode_cards")
        val calendarHideSpoilers = booleanPreferencesKey("calendar_hide_spoilers")
        val calendarCountry = stringPreferencesKey("calendar_country")
        val calendarSkipNewsTalk = booleanPreferencesKey("calendar_skip_news_talk")
        val calendarView = stringPreferencesKey("calendar_view")
        val calendarHideWatched = booleanPreferencesKey("calendar_hide_watched")
        val use24HourClock = booleanPreferencesKey("use_24h_clock")
        val calendarFollows = stringPreferencesKey("calendar_follows")
        val calendarHiddenShows = stringPreferencesKey("calendar_hidden_shows")
    }

    private fun profileBoolKey(base: Preferences.Key<Boolean>, profileId: Int): Preferences.Key<Boolean> =
        if (profileId <= 1) base else booleanPreferencesKey("${base.name}_p$profileId")

    private fun profileStringKey(base: Preferences.Key<String>, profileId: Int): Preferences.Key<String> =
        if (profileId <= 1) base else stringPreferencesKey("${base.name}_p$profileId")

    private fun readSettings(p: Preferences, profileId: Int): CalendarSettings {
        val d = CalendarSettings()
        return CalendarSettings(
            calendarInMenu = p[profileBoolKey(Keys.calendarInMenu, profileId)] ?: d.calendarInMenu,
            calendarOff = p[profileBoolKey(Keys.calendarOff, profileId)] ?: d.calendarOff,
            calendarUpcomingRow = p[profileBoolKey(Keys.calendarUpcomingRow, profileId)] ?: d.calendarUpcomingRow,
            calendarNewEpisodeCards = p[profileBoolKey(Keys.calendarNewEpisodeCards, profileId)] ?: d.calendarNewEpisodeCards,
            calendarHideSpoilers = p[profileBoolKey(Keys.calendarHideSpoilers, profileId)] ?: d.calendarHideSpoilers,
            calendarCountry = p[profileStringKey(Keys.calendarCountry, profileId)] ?: d.calendarCountry,
            calendarSkipNewsTalk = p[profileBoolKey(Keys.calendarSkipNewsTalk, profileId)] ?: d.calendarSkipNewsTalk,
            calendarView = p[profileStringKey(Keys.calendarView, profileId)] ?: d.calendarView,
            calendarHideWatched = p[profileBoolKey(Keys.calendarHideWatched, profileId)] ?: d.calendarHideWatched,
            use24HourClock = p[profileBoolKey(Keys.use24HourClock, profileId)] ?: d.use24HourClock
        )
    }

    val settings: Flow<CalendarSettings> = combine(store.data, profileManager.activeProfileId) { p, id ->
        readSettings(p, id)
    }.distinctUntilChanged()

    suspend fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        val id = profileManager.activeProfileId.value
        store.edit { p ->
            val current = readSettings(p, id)
            val next = transform(current)
            p[profileBoolKey(Keys.calendarInMenu, id)] = next.calendarInMenu
            p[profileBoolKey(Keys.calendarOff, id)] = next.calendarOff
            p[profileBoolKey(Keys.calendarUpcomingRow, id)] = next.calendarUpcomingRow
            p[profileBoolKey(Keys.calendarNewEpisodeCards, id)] = next.calendarNewEpisodeCards
            p[profileBoolKey(Keys.calendarHideSpoilers, id)] = next.calendarHideSpoilers
            p[profileStringKey(Keys.calendarCountry, id)] = next.calendarCountry
            p[profileBoolKey(Keys.calendarSkipNewsTalk, id)] = next.calendarSkipNewsTalk
            p[profileStringKey(Keys.calendarView, id)] = next.calendarView
            p[profileBoolKey(Keys.calendarHideWatched, id)] = next.calendarHideWatched
            p[profileBoolKey(Keys.use24HourClock, id)] = next.use24HourClock
        }
    }

    val calendarFollows: Flow<String> = combine(store.data, profileManager.activeProfileId) { p, id ->
        p[profileStringKey(Keys.calendarFollows, id)] ?: ""
    }.distinctUntilChanged()

    suspend fun editCalendarFollows(transform: (String) -> String) {
        val id = profileManager.activeProfileId.value
        val key = profileStringKey(Keys.calendarFollows, id)
        store.edit { p -> p[key] = transform(p[key] ?: "") }
    }

    val calendarHiddenShows: Flow<String> = combine(store.data, profileManager.activeProfileId) { p, id ->
        p[profileStringKey(Keys.calendarHiddenShows, id)] ?: ""
    }.distinctUntilChanged()

    suspend fun editCalendarHiddenShows(transform: (String) -> String) {
        val id = profileManager.activeProfileId.value
        val key = profileStringKey(Keys.calendarHiddenShows, id)
        store.edit { p -> p[key] = transform(p[key] ?: "") }
    }
}
