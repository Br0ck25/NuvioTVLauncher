package com.nuvio.tv.youtube

/** Builds a WebVTT segment with timestamps relative to its start, including cues crossing its edges. */
internal object YouTubeCaptionSegments {
    const val DURATION_SECONDS = 60L
    private val timing = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{3})")

    fun extract(file: String, number: Long): String {
        if (number < 0 || number > (Long.MAX_VALUE - DURATION_SECONDS * 1000) / (DURATION_SECONDS * 1000)) return "WEBVTT\n\n"
        val start = number * DURATION_SECONDS * 1000
        val end = start + DURATION_SECONDS * 1000
        val result = StringBuilder("WEBVTT\n\n")
        for (block in file.replace("\r\n", "\n").split(Regex("\n{2,}"))) {
            val lines = block.lines()
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) continue
            val match = timing.find(lines[index]) ?: continue
            fun time(i: Int) = (match.groupValues[i].toLongOrNull() ?: 0L) * 3_600_000 +
                match.groupValues[i + 1].toLong() * 60_000 + match.groupValues[i + 2].toLong() * 1_000 + match.groupValues[i + 3].toLong()
            val cueStart = time(1)
            val cueEnd = time(5)
            if (cueEnd <= start || cueStart >= end) continue
            result.append(stamp((cueStart - start).coerceAtLeast(0))).append(" --> ")
                .append(stamp((cueEnd - start).coerceAtMost(end - start))).append('\n')
            result.append(lines.drop(index + 1).joinToString("\n")).append("\n\n")
        }
        return result.toString()
    }

    private fun stamp(ms: Long): String = "%02d:%02d:%02d.%03d".format(
        java.util.Locale.US, ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000
    )
}
