package com.nuvio.tv.reshaped.livetv.lists

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading what the My Lists server sends for `/api/nuvio-tv/schedules/<target>` and `/schedule/…`.
 * Kept apart from [ListChannels] so the shape both ends agree on has a test (see
 * `server/schedules_sample.json`, a trimmed copy of the Worker's real answer).
 */

/** The programmes in one channel's `slots` array. An entry without a video id or a positive length is skipped. */
internal fun parseServerSlots(array: JSONArray): List<ListSlot> =
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val videoId = item.optString("videoId").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val start = item.optLong("startMs", -1L)
        val end = item.optLong("endMs", -1L)
        if (start < 0L || end <= start) return@mapNotNull null
        ListSlot(
            itemId = item.optString("itemId").ifBlank { videoId.substringBefore(':') },
            itemType = item.optString("type").ifBlank { "series" },
            videoId = videoId,
            season = item.optInt("season", -1).takeIf { it >= 0 && !item.isNull("season") },
            episode = item.optInt("episode", -1).takeIf { it >= 0 && !item.isNull("episode") },
            title = item.optString("title"),
            description = item.optString("description").takeIf { it.isNotBlank() && !item.isNull("description") },
            image = item.optString("image").takeIf { it.isNotBlank() && !item.isNull("image") },
            startMs = start,
            endMs = end,
        )
    }

/** The batch answer as channel id to programmes; a channel the server listed as missing, or with none usable, is left out. */
internal fun parseServerSchedules(root: JSONObject, channelIds: List<String>): Map<String, List<ListSlot>> {
    if (!root.optBoolean("ok")) return emptyMap()
    val schedules = root.optJSONObject("schedules") ?: return emptyMap()
    val result = LinkedHashMap<String, List<ListSlot>>()
    channelIds.forEach { id ->
        val slots = schedules.optJSONArray(id)?.let(::parseServerSlots).orEmpty()
        if (slots.isNotEmpty()) result[id] = slots
    }
    return result
}
