package com.nuvio.tv.music

import java.util.Locale

/**
 * Stable keys for "is this the same recording?" checks inside the local Music library.
 *
 * Spotify and YouTube disagree about packaging - "(Official Audio)", "Remastered 2011",
 * "feat. ..." - so a raw title/artist comparison reports duplicates and misses at the same time.
 * The key below strips that packaging, keeps punctuation out, and credits the lead artist only.
 */
internal object MusicMatchKeys {
    private val BRACKETS = Regex("\\(([^)]*)\\)|\\[([^]]*)\\]")
    /** Packaging that never changes which recording it is. */
    private val PACKAGING_SUFFIX = Regex(
        "\\s+-\\s+((\\d{4}\\s+)?remaster(ed)?|official[^-]*|audio|video|lyrics)" +
            "(\\s+\\d{4})?\\s*$"
    )
    /** Packaging that does change the take. */
    private val VERSION_SUFFIX = Regex(
        "\\s+-\\s+(.*\\b(live|remix|acoustic|instrumental|demo|edit|version|sped|slowed|" +
            "nightcore|orchestral|piano|cover|extended|radio)\\b.*)$"
    )
    private val FEATURING = Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*$")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val VERSION_WORDS = setOf(
        "live", "remix", "acoustic", "instrumental", "demo", "edit", "version",
        "sped", "slowed", "nightcore", "orchestral", "piano", "cover", "extended", "radio"
    )

    fun song(title: String, artist: String): String =
        "${titleKey(title)}|${artistKey(artist)}"

    fun titleKey(value: String): String {
        var text = value.lowercase(Locale.ROOT).trim()
        text = text.replace("'", "").replace("\u2019", "")
        val versions = mutableListOf<String>()
        text = BRACKETS.replace(text) { match ->
            val inner = match.groupValues[1].ifBlank { match.groupValues[2] }
            versionWords(inner).takeIf { it.isNotEmpty() }?.let { versions += it }
            " "
        }
        text = text.replace(VERSION_SUFFIX) { match ->
            versionWords(match.groupValues[1]).takeIf { it.isNotEmpty() }?.let { versions += it }
            " "
        }
        text = text.replace(PACKAGING_SUFFIX, " ")
        text = text.replace(FEATURING, " ")
        val core = NON_WORD.replace(text, " ").trim()
        val version = versions.distinct().joinToString(" ")
        return listOf(core, version).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun versionWords(value: String): String {
        val words = NON_WORD.replace(value.lowercase(Locale.ROOT), " ")
            .split(' ')
            .filter { it in VERSION_WORDS }
        return words.distinct().joinToString(" ")
    }

    fun artistKey(value: String): String {
        var text = value.lowercase(Locale.ROOT).trim()
        text = text.replace("'", "").replace("\u2019", "")
        text = text.substringBefore(",").substringBefore(" & ")
        text = text.substringBefore(" feat").substringBefore(" ft.")
        return NON_WORD.replace(text, " ").trim()
    }
}
