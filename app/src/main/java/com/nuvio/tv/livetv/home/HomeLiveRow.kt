package com.nuvio.tv.livetv.home

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** A configurable Live TV, Sports, or On Demand row on Home. */
@Immutable
data class HomeLiveRow(
    val id: String,
    val kind: String,
    val source: String,
    val title: String = "",
    val sort: String = SORT_YOURS,
    val limit: Int = ALL,
    val showNext: Boolean = true,
    val hidePlaceholders: Boolean = false,
    val opensGuide: Boolean = false,
    val playsGame: Boolean = false,
    val startingSoon: Boolean = true,
    val shape: String = SHAPE_AUTO,
    val after: String = "",
    val label: String = ""
) {
    val key: String get() = KEY_PREFIX + id

    fun <T> capped(items: List<T>): List<T> = if (limit == ALL) items else items.take(limit)

    companion object {
        const val KEY_PREFIX = "livetv_"
        const val KIND_CHANNELS = "channels"
        const val KIND_SPORTS = "sports"
        const val KIND_VOD = "ondemand"
        const val VOD_RECENT_MOVIES = "recent:movie"
        const val VOD_RECENT_SERIES = "recent:series"
        const val VOD_CONTINUE = "continue"
        const val VOD_GROUP_PREFIX = "group:"
        const val SHAPE_AUTO = "auto"
        const val SHAPE_PORTRAIT = "portrait"
        const val SHAPE_LANDSCAPE = "landscape"
        const val SORT_YOURS = "yours"
        const val SORT_NUMBER = "number"
        const val SORT_ENDING = "ending"
        const val MAX_LIMIT = 500
        const val ALL = 0

        fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)
        fun idOf(key: String): String? = key.takeIf(::isKey)?.removePrefix(KEY_PREFIX)

        fun channels(group: String) = HomeLiveRow(newId(), KIND_CHANNELS, group)
        fun sports(source: String = "") = HomeLiveRow(newId(), KIND_SPORTS, source)
        fun vod(source: String, label: String = "") = HomeLiveRow(newId(), KIND_VOD, source, label = label)

        fun decode(json: String?): List<HomeLiveRow> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val value = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = value.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val kind = value.optString("kind").takeIf {
                        it == KIND_CHANNELS || it == KIND_SPORTS || it == KIND_VOD
                    } ?: return@mapNotNull null
                    HomeLiveRow(
                        id = id,
                        kind = kind,
                        source = value.optString("source"),
                        title = value.optString("title"),
                        sort = value.optString("sort", SORT_YOURS).takeIf {
                            it == SORT_YOURS || it == SORT_NUMBER || it == SORT_ENDING
                        } ?: SORT_YOURS,
                        limit = value.optInt("limit", ALL).coerceIn(0, MAX_LIMIT),
                        showNext = value.optBoolean("showNext", true),
                        hidePlaceholders = value.optBoolean("hidePlaceholders", false),
                        opensGuide = value.optBoolean("opensGuide", false),
                        playsGame = value.optBoolean("playsGame", false),
                        startingSoon = value.optBoolean("startingSoon", true),
                        shape = value.optString("shape", SHAPE_AUTO).takeIf {
                            it == SHAPE_AUTO || it == SHAPE_PORTRAIT || it == SHAPE_LANDSCAPE
                        } ?: SHAPE_AUTO,
                        after = value.optString("after"),
                        label = value.optString("label")
                    )
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        fun encode(rows: List<HomeLiveRow>): String = JSONArray().apply {
            rows.distinctBy { it.id }.forEach { row ->
                put(JSONObject().apply {
                    put("id", row.id)
                    put("kind", row.kind)
                    put("source", row.source)
                    put("title", row.title)
                    put("sort", row.sort)
                    put("limit", row.limit.coerceIn(0, MAX_LIMIT))
                    put("showNext", row.showNext)
                    put("hidePlaceholders", row.hidePlaceholders)
                    put("opensGuide", row.opensGuide)
                    put("playsGame", row.playsGame)
                    put("startingSoon", row.startingSoon)
                    put("shape", row.shape)
                    put("after", row.after)
                    put("label", row.label)
                })
            }
        }.toString()

        private fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) +
            Integer.toString((Math.random() * 1296).toInt(), 36)
    }
}

fun isLiveHomeRowKey(key: String): Boolean = HomeLiveRow.isKey(key)
