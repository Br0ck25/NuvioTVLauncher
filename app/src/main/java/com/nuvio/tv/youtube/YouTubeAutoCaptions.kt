package com.nuvio.tv.youtube

/**
 * YouTube's speech-recognition captions roll up. In its WebVTT file each caption holds two lines: the line before
 * on top (plain) and the new line under it (with a timing tag on every word), followed by a ten-millisecond
 * caption that shows the old line alone. Shown as they come, the old line stays up while the new one appears.
 *
 * [tidy] leaves one line per caption, the newest, with no timing tags, no repeats, and each caption ending where
 * the next begins, so the previous line is gone when the next one appears.
 */
object YouTubeAutoCaptions {
    private class Cue(val start: Long, var end: Long, val text: String)

    private val tags = Regex("<[^>]*>")
    private val space = Regex("[\\s\\u00A0]+")
    private val timing = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")

    /**
     * Auto-generated captions use inline timing tags such as `<00:00:19.760>` and `<c>`.
     * A translated track can lose the `kind=asr` query parameter, so detect the rolling
     * format from the file itself instead of trusting the URL alone.
     */
    fun looksAutomatic(file: String): Boolean =
        file.contains("<c>") || Regex("<\\d{2}:\\d{2}:\\d{2}\\.\\d{3}>").containsMatchIn(file)

    /**
     * Cleans one cue's text: repeated identical lines are collapsed, and when a rolling caption
     * carries the previous cue's line(s) on top, only the new line is kept. A single held line is
     * returned as it is so the caller can extend the previous cue instead of drawing it twice.
     */
    fun cleanRollingCue(text: String, previousText: String?): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val unique = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line !in unique) unique.add(line)
        }
        if (unique.size <= 1) return unique[0]
        val previousLines = previousText
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val kept = if (previousLines.isEmpty()) unique else unique.dropWhile { it in previousLines }
        return if (kept.isEmpty()) unique[0] else kept.joinToString("\n")
    }

    /** The captions in [file] (WebVTT) with only each caption's newest line. Anything else is returned as it is. */
    fun tidy(file: String): String {
        val text = file.replace("\r\n", "\n")
        if (!text.trimStart('﻿', ' ', '\n').startsWith("WEBVTT")) return file
        val cues = ArrayList<Cue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val at = lines.indexOfFirst { "-->" in it }
            if (at < 0) continue
            val m = timing.find(lines[at]) ?: continue
            val start = millis(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            val end = millis(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
            val newest = lines.drop(at + 1)
                .map { it.replace(tags, "").replace(space, " ").trim() }
                .lastOrNull { it.isNotEmpty() } ?: continue
            val last = cues.lastOrNull()
            // The old line shown again on its own for a moment: the same caption, lasting a little longer.
            if (last != null && last.text == newest) last.end = maxOf(last.end, end)
            else cues += Cue(start, end, newest)
        }
        if (cues.isEmpty()) return file
        // A caption never carries on under the next one.
        for (i in 0 until cues.lastIndex) {
            val next = cues[i + 1].start
            if (cues[i].end > next && next > cues[i].start) cues[i].end = next
        }
        return buildString {
            append("WEBVTT\n\n")
            for (c in cues) {
                append(stamp(c.start)).append(" --> ").append(stamp(maxOf(c.end, c.start + 1))).append('\n')
                append(c.text).append("\n\n")
            }
        }
    }

    private fun millis(h: String, m: String, s: String, ms: String): Long =
        (h.toLongOrNull() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()

    private fun stamp(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1_000 % 60
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms % 1_000)
    }
}
