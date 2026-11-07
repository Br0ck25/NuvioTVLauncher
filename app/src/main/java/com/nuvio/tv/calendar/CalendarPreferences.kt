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

    private fun profileKey(base: Preferences.Key<String>, profileId: Int): Preferences.Key<String> =
        if (profileId <= 1) base else stringPreferencesKey("${base.name}_p$profileId")

    val settings: Flow<CalendarSettings> = store.data.map { p ->
        val d = CalendarSettings()
        CalendarSettings(
            calendarInMenu = p[Keys.calendarInMenu] ?: d.calendarInMenu,
            calendarOff = p[Keys.calendarOff] ?: d.calendarOff,
            calendarUpcomingRow = p[Keys.calendarUpcomingRow] ?: d.calendarUpcomingRow,
            calendarNewEpisodeCards = p[Keys.calendarNewEpisodeCards] ?: d.calendarNewEpisodeCards,
            calendarHideSpoilers = p[Keys.calendarHideSpoilers] ?: d.calendarHideSpoilers,
            calendarCountry = p[Keys.calendarCountry] ?: d.calendarCountry,
            calendarSkipNewsTalk = p[Keys.calendarSkipNewsTalk] ?: d.calendarSkipNewsTalk,
            calendarView = p[Keys.calendarView] ?: d.calendarView,
            calendarHideWatched = p[Keys.calendarHideWatched] ?: d.calendarHideWatched,
            use24HourClock = p[Keys.use24HourClock] ?: d.use24HourClock
        )
    }.distinctUntilChanged()

    suspend fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        store.edit { p ->
            val current = CalendarSettings(
                calendarInMenu = p[Keys.calendarInMenu] ?: true,
                calendarOff = p[Keys.calendarOff] ?: false,
                calendarUpcomingRow = p[Keys.calendarUpcomingRow] ?: true,
                calendarNewEpisodeCards = p[Keys.calendarNewEpisodeCards] ?: true,
                calendarHideSpoilers = p[Keys.calendarHideSpoilers] ?: true,
                calendarCountry = p[Keys.calendarCountry] ?: "US",
                calendarSkipNewsTalk = p[Keys.calendarSkipNewsTalk] ?: true,
                calendarView = p[Keys.calendarView] ?: "MONTH",
                calendarHideWatched = p[Keys.calendarHideWatched] ?: false,
                use24HourClock = p[Keys.use24HourClock] ?: false
            )
            val next = transform(current)
            p[Keys.calendarInMenu] = next.calendarInMenu
            p[Keys.calendarOff] = next.calendarOff
            p[Keys.calendarUpcomingRow] = next.calendarUpcomingRow
            p[Keys.calendarNewEpisodeCards] = next.calendarNewEpisodeCards
            p[Keys.calendarHideSpoilers] = next.calendarHideSpoilers
            p[Keys.calendarCountry] = next.calendarCountry
            p[Keys.calendarSkipNewsTalk] = next.calendarSkipNewsTalk
            p[Keys.calendarView] = next.calendarView
            p[Keys.calendarHideWatched] = next.calendarHideWatched
            p[Keys.use24HourClock] = next.use24HourClock
        }
    }

    val calendarFollows: Flow<String> = combine(store.data, profileManager.activeProfileId) { p, id ->
        p[profileKey(Keys.calendarFollows, id)] ?: p[Keys.calendarFollows] ?: ""
    }.distinctUntilChanged()

    suspend fun editCalendarFollows(transform: (String) -> String) {
        val id = profileManager.activeProfileId.value
        val key = profileKey(Keys.calendarFollows, id)
        store.edit { p -> p[key] = transform(p[key] ?: p[Keys.calendarFollows] ?: "") }
    }

    val calendarHiddenShows: Flow<String> = combine(store.data, profileManager.activeProfileId) { p, id ->
        p[profileKey(Keys.calendarHiddenShows, id)] ?: p[Keys.calendarHiddenShows] ?: ""
    }.distinctUntilChanged()

    suspend fun editCalendarHiddenShows(transform: (String) -> String) {
        val id = profileManager.activeProfileId.value
        val key = profileKey(Keys.calendarHiddenShows, id)
        store.edit { p -> p[key] = transform(p[key] ?: p[Keys.calendarHiddenShows] ?: "") }
    }
}
