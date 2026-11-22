@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import java.util.Locale

/**
 * Removes ASS/SSA Dialogue lines that would draw the same text twice at the same time.
 *
 * Some Arabic subtitle files carry two events for one line (often two styles or two conversions of
 * the same cue). libass draws every event it is given, so those files show each line twice. Media3's
 * basic SSA parser has the same result, except its cues are then merged by
 * [PlayerSubtitleUtils.mergeOverlappingCues]. Remove the repeated events before either renderer
 * sees them.
 */
internal object PlayerAssDialogueDedupe {

    /** Returns replacement bytes for the [length] bytes at [offset], or null when nothing repeats. */
    fun dedupe(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length <= 0 || offset < 0 || offset + length > data.size) return null
        val text = runCatching {
            String(data, offset, length, Charsets.UTF_8)
        }.getOrNull() ?: return null
        // UTF-16 and other encodings would be damaged by decoding as UTF-8. ASS files from addons
        // are UTF-8; leave anything that does not look like UTF-8 text alone.
        if ('\u0000' in text || '\uFFFD' in text || !text.contains("Dialogue:", ignoreCase = true)) {
            return null
        }
        return dedupeText(text)?.toByteArray(Charsets.UTF_8)
    }

    /** Returns [text] without repeated Dialogue events, or null when none were removed. */
    fun dedupeText(text: String): String? {
        if (!text.contains("Dialogue:", ignoreCase = true)) return null
        val newline = when {
            "\r\n" in text -> "\r\n"
            "\r" in text -> "\r"
            else -> "\n"
        }
        val lines = text.split(Regex("\r\n|\n|\r"))
        val keptByKey = HashMap<String, Int>()
        var changed = false
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            if (!line.trimStart().startsWith("Dialogue:", ignoreCase = true)) {
                kept.add(line)
                continue
            }
            val key = dialogueKey(line)
            if (key == null) {
                kept.add(line)
                continue
            }
            val existingIndex = keptByKey[key]
            if (existingIndex == null) {
                keptByKey[key] = kept.size
                kept.add(line)
            } else {
                changed = true
                // Keep the copy that stays on screen longest; they are the same line.
                if (dialogueEndMs(line) > dialogueEndMs(kept[existingIndex])) {
                    kept[existingIndex] = line
                }
            }
        }
        return if (changed) kept.joinToString(newline) else null
    }

    /**
     * The event's start plus its visible text. End/style/name/margins deliberately do not take
     * part: two events for the same line starting together are the duplicate that shows twice,
     * even when a converter gave them slightly different end times.
     */
    private fun dialogueKey(line: String): String? {
        val body = line.trimStart().substringAfter(':', "").trimStart()
        if (body.isEmpty()) return null
        val fields = body.split(',', limit = 10)
        if (fields.size < 10) return null
        val start = fields[1].trim()
        val text = fields[9].trim()
        if (start.isEmpty() || text.isEmpty()) return null
        return String.format(Locale.US, "%s\u0000%s", start, text)
    }

    private fun dialogueEndMs(line: String): Long {
        val body = line.trimStart().substringAfter(':', "").trimStart()
        val end = body.split(',', limit = 10).getOrNull(2)?.trim().orEmpty()
        val parts = end.split(':')
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: return 0L
        val minutes = parts[1].toLongOrNull() ?: return 0L
        val seconds = parts[2].replace(',', '.').toDoubleOrNull() ?: return 0L
        return ((hours * 3600L + minutes * 60L) * 1000L) + (seconds * 1000.0).toLong()
    }
}

/** Runs [PlayerAssDialogueDedupe] in front of libass's full-file parser. */
internal class AssDialogueDedupeParser(
    private val delegate: SubtitleParser,
) : SubtitleParser {

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val deduped = PlayerAssDialogueDedupe.dedupe(data, offset, length)
        if (deduped != null) {
            delegate.parse(deduped, 0, deduped.size, outputOptions, output)
        } else {
            delegate.parse(data, offset, length, outputOptions, output)
        }
    }

    override fun reset() = delegate.reset()

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
}
