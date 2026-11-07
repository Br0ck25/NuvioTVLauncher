package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.calendar.CalendarEntryPoint
import com.nuvio.tv.calendar.CalendarNotice
import com.nuvio.tv.calendar.CalendarSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

@Composable
fun CalendarSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val entryPoint = remember {
        runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java)
        }.getOrNull()
    } ?: return
    val prefs = remember { entryPoint.calendarPreferences() }
    val repo = remember { entryPoint.calendarRepository() }
    val settings by prefs.settings.collectAsState(initial = CalendarSettings())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val countries = listOf(
        "US" to "United States",
        "GB" to "United Kingdom",
        "CA" to "Canada",
        "AU" to "Australia",
        "DE" to "Germany",
        "FR" to "France",
        "JP" to "Japan",
        "BR" to "Brazil",
        "IN" to "India"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Calendar",
            subtitle = "Upcoming episodes, movie release dates, and Home screen release cards"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "cal_enabled") {
                        SettingsToggleRow(
                            title = "Enable Calendar",
                            subtitle = "Show Calendar in Nuvio, including Home screen rows, episode bells, and release tracking",
                            checked = !settings.calendarOff,
                            onToggle = {
                                scope.launch { prefs.updateSettings { it.copy(calendarOff = !it.calendarOff) } }
                            },
                            modifier = if (initialFocusRequester != null) {
                                Modifier.focusRequester(initialFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                    if (!settings.calendarOff) {
                        item(key = "cal_upcoming_row") {
                            SettingsToggleRow(
                                title = "Upcoming Row on Home",
                                subtitle = "Show upcoming episodes for your shows in the Home screen's Upcoming row",
                                checked = settings.calendarUpcomingRow,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarUpcomingRow = !it.calendarUpcomingRow) } }
                                }
                            )
                        }
                        item(key = "cal_new_ep_cards") {
                            SettingsToggleRow(
                                title = "New Episode & Movie Cards on Home",
                                subtitle = "Show newly aired episodes and home-release movies in Continue Watching for followed titles",
                                checked = settings.calendarNewEpisodeCards,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarNewEpisodeCards = !it.calendarNewEpisodeCards) } }
                                }
                            )
                        }
                        item(key = "cal_hide_spoilers") {
                            SettingsToggleRow(
                                title = "Hide Episode Spoilers",
                                subtitle = "Hide episode titles and thumbnails for episodes ahead of your current watch progress",
                                checked = settings.calendarHideSpoilers,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarHideSpoilers = !it.calendarHideSpoilers) } }
                                }
                            )
                        }
                        item(key = "cal_hide_watched") {
                            SettingsToggleRow(
                                title = "Unwatched Only in Recently Aired",
                                subtitle = "Hide episodes you have already watched from the Recently Aired tab",
                                checked = settings.calendarHideWatched,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarHideWatched = !it.calendarHideWatched) } }
                                }
                            )
                        }
                        item(key = "cal_skip_news") {
                            SettingsToggleRow(
                                title = "Skip News & Talk Shows in Airing Today",
                                subtitle = "Filter out daily news, talk shows, and panel shows from Airing Today",
                                checked = settings.calendarSkipNewsTalk,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarSkipNewsTalk = !it.calendarSkipNewsTalk) } }
                                }
                            )
                        }
                        item(key = "cal_24h") {
                            SettingsToggleRow(
                                title = "24-Hour Clock",
                                subtitle = "Display air times in 24-hour format",
                                checked = settings.use24HourClock,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(use24HourClock = !it.use24HourClock) } }
                                }
                            )
                        }
                        item(key = "cal_view") {
                            val views = listOf("LIST" to "List", "WEEK" to "Week", "MONTH" to "Month")
                            val currentLabel = views.firstOrNull { it.first == settings.calendarView }?.second ?: "List"
                            SettingsActionRow(
                                title = "Default Calendar View",
                                subtitle = "Choose between List, Week, and Month layouts",
                                value = currentLabel,
                                onClick = {
                                    val idx = views.indexOfFirst { it.first == settings.calendarView }
                                    val next = views[(idx + 1) % views.size].first
                                    scope.launch { prefs.updateSettings { it.copy(calendarView = next) } }
                                }
                            )
                        }
                        item(key = "cal_country") {
                            val currentCountry = countries.firstOrNull { it.first == settings.calendarCountry }?.second ?: settings.calendarCountry
                            SettingsActionRow(
                                title = "Release Country",
                                subtitle = "Used for Airing Today schedules and movie release dates",
                                value = currentCountry,
                                onClick = {
                                    val idx = countries.indexOfFirst { it.first == settings.calendarCountry }
                                    val next = countries[(idx + 1) % countries.size].first
                                    scope.launch { prefs.updateSettings { it.copy(calendarCountry = next) } }
                                }
                            )
                        }
                        item(key = "cal_sidebar") {
                            SettingsToggleRow(
                                title = "Always Show Calendar in Sidebar",
                                subtitle = "Show Calendar in the navigation sidebar even before following any shows",
                                checked = settings.calendarInMenu,
                                onToggle = {
                                    scope.launch { prefs.updateSettings { it.copy(calendarInMenu = !it.calendarInMenu) } }
                                }
                            )
                        }
                        item(key = "cal_refresh") {
                            SettingsActionRow(
                                title = "Refresh Calendar Now",
                                subtitle = "Check TVmaze and TMDB for updated episode and movie dates",
                                onClick = {
                                    repo.refresh(force = true)
                                    CalendarNotice.show("Refreshing Calendar…")
                                }
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}
