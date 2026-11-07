package com.nuvio.tv.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    val repo: CalendarRepository,
    private val prefs: CalendarPreferences
) : ViewModel() {
    val state = repo.state
    val airing = repo.airing
    val follows = repo.follows
    val hidden = repo.hiddenShows
    val settings = repo.settings

    val use24h: StateFlow<Boolean> = prefs.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    init {
        repo.start()
        // Opened: a fresh look if the last one was a while ago.
        if (System.currentTimeMillis() - repo.state.value.updatedAt > 30 * 60_000L) repo.refresh(force = false)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    fun refresh() = repo.refresh(force = true)

    fun loadAiring(force: Boolean = false) = repo.loadAiring(force = force)

    fun setView(view: String) {
        viewModelScope.launch { prefs.updateSettings { it.copy(calendarView = view) } }
    }

    /** Airing Today's "News & talk" filter: leaves news and talk shows out (or puts them back). */
    fun setSkipNewsTalk(skip: Boolean) {
        viewModelScope.launch {
            prefs.updateSettings { it.copy(calendarSkipNewsTalk = skip) }
            delay(300) // the saved setting reaches the Calendar first
            repo.loadAiring(force = true)
        }
    }

    fun toggleFollow(e: CalendarEntry) {
        val existing = repo.follows.value.firstOrNull { it.id == e.key }
        if (existing != null) { toggleFollow(existing); return }
        toggleFollow(
            CalendarFollow(
                id = e.contentId,
                type = e.type,
                name = e.title,
                poster = e.poster,
                background = e.background,
                imdbId = e.contentId.takeIf { it.startsWith("tt") },
                addonBaseUrl = e.addonBaseUrl
            )
        )
    }

    fun toggleFollow(f: CalendarFollow) {
        viewModelScope.launch {
            val on = repo.toggleFollow(f)
            CalendarNotice.show(
                when {
                    f.isAiring && on -> "${f.name} is on your Calendar"
                    f.isAiring -> "${f.name} taken off your Calendar"
                    on -> "Bell on: new episodes of ${f.name} will show on Home and in your Calendar"
                    else -> "Bell turned off for ${f.name}"
                }
            )
        }
    }

    fun hide(e: CalendarEntry) {
        viewModelScope.launch {
            repo.hide(e.contentId, e.title)
            CalendarNotice.show("${e.title} is hidden from the Calendar (Hidden shows brings it back)")
        }
    }

    fun unhide(h: CalendarHidden) {
        viewModelScope.launch { repo.unhide(h.id); repo.refresh(force = false) }
    }

    fun setHideWatched(hide: Boolean) {
        viewModelScope.launch { prefs.updateSettings { it.copy(calendarHideWatched = hide) } }
    }
}
