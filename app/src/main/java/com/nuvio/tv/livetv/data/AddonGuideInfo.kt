package com.nuvio.tv.livetv.data

import android.util.Base64
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Guide details embedded in a live addon's channel id (CNCVerse's event catalogs do this). */
data class AddonEventInfo(
    val title: String,
    val description: String?,
    val category: String?,
    val startMs: Long,
    val endMs: Long
) {
    fun toProgram(channel: LiveChannel): EpgProgram = EpgProgram(
        startMs = startMs,
        stopMs = endMs,
        title = title,
        description = description,
        category = category,
        icon = channel.logo
    )
}

/**
 * Some live catalogs (CNCVerse's PlayZTV/SKTECH event lists, for example) put the event's
 * title, teams and start time in the catalog id as base64-encoded JSON. That is the only guide
 * information those channels have, so it is turned into a single programme entry.
 *
 * 24/7 channels don't carry this data; they keep their normal name and can still be matched to
 * an XMLTV guide by name.
 */
object AddonGuideInfo {
    private const val DEFAULT_DURATION_MS = 3 * 60 * 60_000L
    private val cache = java.util.Collections.synchronizedMap(HashMap<String, AddonEventInfo?>())

    fun event(channelId: String): AddonEventInfo? {
        if (!channelId.startsWith("cnc:", ignoreCase = true)) return null
        if (cache.containsKey(channelId)) return cache[channelId]
        val parsed = runCatching { parse(channelId) }.getOrNull()
        if (cache.size > 4096) cache.clear()
        cache[channelId] = parsed
        return parsed
    }

    private fun parse(channelId: String): AddonEventInfo? {
        val encoded = channelId.substringAfter(':', "")
        if (encoded.isBlank()) return null
        val decoded = decode(encoded) ?: return null
        val start = decoded.indexOf('{')
        val end = decoded.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = JSONObject(decoded.substring(start, end + 1))
        val info = root.optJSONObject("eventInfo")

        val startMs = parseTime(info?.optString("startTime") ?: root.optString("startTime"))
            ?: return null
        val endMs = parseTime(info?.optString("endTime") ?: root.optString("endTime"))
            ?.takeIf { it > startMs }
            ?: (startMs + DEFAULT_DURATION_MS)

        val teamA = info?.optString("teamA").orEmpty().trim()
        val teamB = info?.optString("teamB").orEmpty().trim()
        val eventName = info?.optString("eventName").orEmpty().trim()
        val eventCat = info?.optString("eventCat").orEmpty().trim()
        val title = when {
            teamA.isNotBlank() && teamB.isNotBlank() -> "$teamA vs $teamB"
            eventName.isNotBlank() -> eventName
            else -> root.optString("title").trim()
        }.takeIf { it.isNotBlank() } ?: return null

        return AddonEventInfo(
            title = title,
            description = listOfNotNull(
                eventName.takeIf { it.isNotBlank() && it != title },
                eventCat.takeIf { it.isNotBlank() }
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            category = eventCat.takeIf { it.isNotBlank() },
            startMs = startMs,
            endMs = endMs
        )
    }

    private fun decode(encoded: String): String? {
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val bytes = runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000L else it }
        val patterns = listOf(
            "yyyy/MM/dd HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss Z",
            "MMM dd, yyyy hh:mm a z",
            "MMM dd, yyyy hh:mm a"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.isLenient = false
                if (pattern.endsWith("'Z'")) format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
