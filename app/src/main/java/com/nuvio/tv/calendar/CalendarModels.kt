package com.nuvio.tv.calendar

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A show (or movie) whose bell is rung: it's on the Calendar and gets a card on Home when it's out. */
@Immutable
data class CalendarFollow(
    /** Nuvio's id for it (usually the IMDb id, "tt…"). */
    val id: String,
    /** "series" or "movie". */
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val imdbId: String? = null,
    val addonBaseUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("name", name)
        .put("poster", poster ?: "").put("background", background ?: "")
        .put("imdb", imdbId ?: "").put("addon", addonBaseUrl ?: "").put("at", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = CalendarFollow(
            id = o.optString("id"),
            type = o.optString("type", "series"),
            name = o.optString("name"),
            poster = o.optString("poster").ifBlank { null },
            background = o.optString("background").ifBlank { null },
            imdbId = o.optString("imdb").ifBlank { null },
            addonBaseUrl = o.optString("addon").ifBlank { null },
            addedAt = o.optLong("at", 0L)
        )

        fun decode(raw: String?): List<CalendarFollow> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

        fun encode(list: List<CalendarFollow>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}

/** A show taken off the Calendar (Hold OK → Hide). */
@Immutable
data class CalendarHidden(val id: String, val name: String) {
    companion object {
        fun decode(raw: String?): List<CalendarHidden> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CalendarHidden(it.optString("id"), it.optString("name")) }
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

        fun encode(list: List<CalendarHidden>): String =
            JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()
    }
}

enum class CalendarKind { EPISODE, MOVIE }

/** Tags beside an entry. */
enum class CalendarTag(val label: String) {
    NEW("New"),
    SERIES_PREMIERE("Series premiere"),
    SEASON_PREMIERE("Season premiere"),
    SEASON_FINALE("Season finale"),
    SERIES_FINALE("Series finale"),
    IN_THEATERS("In theaters"),
    AT_HOME("At home"),
    ON_DISC("On disc")
}

/** Why an entry is on your Calendar. */
enum class CalendarReason { BELL, LIBRARY, WATCHING, AIRING_TODAY }

@Immutable
data class CalendarEntry(
    /** Unique per entry. */
    val key: String,
    val kind: CalendarKind,
    /** Nuvio's id for the show or movie (IMDb "tt…" when known). */
    val contentId: String,
    /** "series" or "movie". */
    val type: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val addonBaseUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The last episode when several come out together (a whole season at once). */
    val lastEpisode: Int? = null,
    val episodeTitle: String? = null,
    val episodeImage: String? = null,
    /** When it airs for you (null: only the day is known). */
    val airMs: Long? = null,
    /** TV episodes: the time TVmaze gives (its first airing, usually the East Coast). */
    val listedAirMs: Long? = null,
    /** TV episodes: the local time to show (West Coast: the delayed airing). */
    val plainAirMs: Long? = null,
    /** The day it airs in your time zone. */
    val day: LocalDate,
    /** The network or streaming service. */
    val network: String? = null,
    /** On a streaming service (no air time). */
    val streaming: Boolean = false,
    val tags: List<CalendarTag> = emptyList(),
    /** Movies: the other release ("In theaters Oct 2"). */
    val note: String? = null,
    val reasons: Set<CalendarReason> = emptySet(),
    /** You've watched it. */
    val watched: Boolean = false,
    /** You haven't reached it yet: its title and picture are hidden. */
    val spoiler: Boolean = false,
    val runtimeMin: Int? = null,
    /** What it's about (the episode's summary, the movie's overview). */
    val description: String? = null
) {
    val episodeCode: String?
        get() = if (season == null || episode == null) null else buildString {
            append("S$season · E$episode")
            if (lastEpisode != null && lastEpisode > episode) append("–E$lastEpisode")
        }

    /** Nuvio's id for this episode ("tt123:2:5"). */
    val videoId: String?
        get() = if (season != null && episode != null && contentId.startsWith("tt")) "$contentId:$season:$episode" else null

    val followed: Boolean get() = CalendarReason.BELL in reasons
}

/** What the Calendar shows. */
@Immutable
data class CalendarState(
    val entries: List<CalendarEntry> = emptyList(),
    /** Still looking up shows. */
    val loading: Boolean = false,
    /** Shows looked up so far / in all (while loading). */
    val progress: Pair<Int, Int>? = null,
    val updatedAt: Long = 0L,
    /** Shows on the Calendar (bells, Library and what you're watching). */
    val showCount: Int = 0,
    val problem: String? = null
)

/** One show's next episode, for its details page. */
@Immutable
data class CalendarShowInfo(
    val next: CalendarEntry?,
    /** "Ended", "Returning series"… (when there's no next episode). */
    val status: String?,
    /** All upcoming episodes by (season, episode) -> when, for the episode list. */
    val upcoming: Map<Pair<Int, Int>, Long> = emptyMap()
)

/**
 * US networks air prime time at the same clock time on the East and West Coasts (the West gets
 * a delayed feed), but TVmaze gives the East Coast moment. On a box set to Pacific (or Alaska /
 * Hawaii) time, a show listed for 8 pm Eastern is shown at 8 pm local; on Mountain time at 7 pm
 * ("8/7c"). Central and Eastern are left as they are (8 pm Eastern is 7 pm Central).
 */
object CalendarAirTimes {
    private val eastern: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

    /** Hours to take off the listed clock time on this box (0 = Pacific or further west), or null: no change. */
    fun westShiftHours(): Int? {
        val zone = java.time.ZoneId.systemDefault()
        val id = zone.id
        val american = id.startsWith("America/") || id.startsWith("US/") || id.startsWith("Canada/") ||
            id.startsWith("Pacific/Honolulu") || id in setOf("PST8PDT", "MST7MDT", "MST", "PST", "HST", "SystemV/PST8", "SystemV/MST7") ||
            zone is java.time.ZoneOffset || id.startsWith("GMT") || id.startsWith("UTC") || id.startsWith("Etc/GMT")
        if (!american) return null
        val std = zone.rules.getStandardOffset(java.time.Instant.now()).totalSeconds / 3600
        return when {
            std in -11..-8 -> 0
            std == -7 -> 1
            else -> null
        }
    }

    /** True when the box is on Mountain, Pacific, Alaska or Hawaii time. */
    fun westOfCentral(): Boolean = westShiftHours() != null

    /**
     * The time [listedMs] airs here: the same clock time (less [westShiftHours]) when the show is on
     * an East Coast network ([networkZone], or, when that isn't known, a listed time that matches
     * the schedule's [scheduleTime] in Eastern time). Otherwise [listedMs] as it is.
     */
    fun local(listedMs: Long?, networkZone: String?, scheduleTime: String?, streaming: Boolean): Long? {
        if (listedMs == null || streaming) return listedMs
        val shift = westShiftHours() ?: return listedMs
        val east = java.time.Instant.ofEpochMilli(listedMs).atZone(eastern)
        val isEast = if (networkZone != null) {
            networkZone.startsWith("America/") && runCatching {
                java.time.ZoneId.of(networkZone).rules.getOffset(east.toInstant()) == east.offset
            }.getOrDefault(false)
        } else {
            scheduleTime != null && runCatching { java.time.LocalTime.parse(scheduleTime) == east.toLocalTime().withSecond(0).withNano(0) }.getOrDefault(false)
        }
        if (!isEast) return listedMs
        return east.toLocalDateTime().minusHours(shift.toLong()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
