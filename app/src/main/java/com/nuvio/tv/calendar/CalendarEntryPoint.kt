package com.nuvio.tv.calendar

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** For screens that aren't given a ViewModel (settings, the details page's bell). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarEntryPoint {
    fun calendarRepository(): CalendarRepository
    fun calendarPreferences(): CalendarPreferences
}
