package com.nuvio.tv.livetv.model

import java.text.Normalizer
import java.util.Locale

/**
 * Tags taken from channel group titles ("US | Entertainment", "AR | Arabic", "Sky Sports AR").
 *
 * A tag is a whole segment or a whole word, never a substring: selecting "AR" matches
 * "AR | Arabic" and "Sky Sports AR", but not "Arena". The scan is manual; the settings screen
 * asks the user to scan, then they pick the tags they want to hide.
 */
internal object LiveTvGroupTags {
    data class Tag(
        val label: String,
        val channelCount: Int,
        val groupCount: Int
    )

    /** Matches group titles against the selected tags, with a per-title cache. */
    class Matcher(tags: Set<String>) {
        private val wanted = tags.mapTo(HashSet()) { normalize(it) }
        private val cache = HashMap<String, Boolean>()

        fun matches(groupTitle: String): Boolean {
            if (wanted.isEmpty()) return false
            return cache.getOrPut(groupTitle) {
                rawTokens(groupTitle).any { normalize(it) in wanted }
            }
        }
    }

    /**
     * Every distinct tag in [channels], with how many channels and groups use it.
     *
     * [countriesOnly] lists only known country names/codes found in the group titles (the
     * common "US | ..." / "Sky Sports AR" style), instead of every word such as
     * "Entertainment" or "Sports". Country matching is accent-insensitive.
     */
    fun scan(channels: List<LiveChannel>, countriesOnly: Boolean = false): List<Tag> {
        val labels = HashMap<String, String>()
        val channelKeys = HashMap<String, MutableSet<String>>()
        val groupIds = HashMap<String, MutableSet<String>>()
        val countryCache = HashMap<String, Set<String>>()
        channels.forEach { channel ->
            val tokens = if (countriesOnly) {
                countryCache.getOrPut(channel.group) { countryTokens(channel.group) }
            } else {
                rawTokens(channel.group)
            }
            tokens.forEach { raw ->
                val key = normalize(raw)
                if (!isUseful(key)) return@forEach
                labels.putIfAbsent(key, raw)
                channelKeys.getOrPut(key) { HashSet() } += channel.key
                groupIds.getOrPut(key) { HashSet() } += channel.groupId
            }
        }
        return labels.map { (key, label) ->
            Tag(
                label = label,
                channelCount = channelKeys[key]?.size ?: 0,
                groupCount = groupIds[key]?.size ?: 0
            )
        }.sortedWith(compareByDescending<Tag> { it.channelCount }.thenBy { it.label.lowercase() })
    }

    fun matches(groupTitle: String, tags: Set<String>): Boolean =
        Matcher(tags).matches(groupTitle)

    /**
     * The group title's whole segments and whole words. "US | Entertainment" gives
     * US and Entertainment; "United States | Sports" also gives United and States;
     * "Sky Sports AR" gives Sky, Sports and AR.
     */
    fun rawTokens(groupTitle: String): Set<String> {
        val out = LinkedHashSet<String>()
        val title = groupTitle.trim()
        if (title.isEmpty()) return out
        for (segment in separators.split(title)) {
            val clean = segment.trim()
            if (clean.isEmpty()) continue
            out += clean
            for (word in words.split(clean)) {
                val token = word.trim()
                if (token.isNotEmpty()) out += token
            }
        }
        return out
    }

    /**
     * Only the country parts of a group title: "US | Entertainment" gives US, "Sky Sports AR"
     * gives AR, and "United States | Sports" gives the whole "United States" rather than the
     * separate words "United" and "States".
     */
    fun countryTokens(groupTitle: String): Set<String> {
        val out = LinkedHashSet<String>()
        val title = groupTitle.trim()
        if (title.isEmpty()) return out
        for (segment in separators.split(title)) {
            val clean = segment.trim()
            if (clean.isEmpty()) continue
            if (fold(clean) in countryWords) {
                out += clean
                continue
            }
            val parts = words.split(clean).map { it.trim() }.filter { it.isNotEmpty() }
            for ((index, part) in parts.withIndex()) {
                val key = fold(part)
                if (key !in countryWords) continue
                val atEdge = index == 0 || index == parts.lastIndex
                val uppercase = part.any { it.isUpperCase() }
                if (key.length == 2) {
                    // Two-letter codes are also ordinary words ("US", "IT", "IN"). Only trust
                    // them at an edge of the segment, and when they were written as a code.
                    if (key in ambiguousCountryCodes && !(atEdge && uppercase)) continue
                    if (!atEdge && !uppercase) continue
                }
                out += part
            }
        }
        return out
    }

    fun normalize(value: String): String =
        value.trim().lowercase().replace(whitespace, " ")

    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(accents, "")
            .trim()
            .lowercase()
            .replace(whitespace, " ")

    private fun isUseful(key: String): Boolean =
        key.length in 2..40 && key.any { it.isLetter() } && key !in ignored

    private val separators = Regex("[|:•/\\\\\\-–—()\\[\\]{},;]+")
    private val words = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")
    private val accents = Regex("\\p{M}+")

    /** Quality/status words that are never useful as a language or country filter. */
    private val ignored = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "vip", "backup", "test", "ppv"
    )

    /** Two-letter ISO codes that are also common English or group-title words. */
    private val ambiguousCountryCodes = setOf(
        "us", "it", "in", "no", "me", "as", "at", "be", "by", "do", "is", "my", "or", "so",
        "to", "tv", "am", "pm", "id", "ai", "io", "cc", "pr", "hr", "fm", "ms", "pa", "md",
        "la", "de"
    )

    /**
     * Normalized ISO country codes and names, plus the short forms IPTV providers commonly use
     * in group titles. Accent-insensitive so "Türkiye" and "Turkiye" match the same entry.
     */
    private val countryWords: Set<String> by lazy {
        val out = HashSet<String>()
        for (code in Locale.getISOCountries()) {
            out += fold(code)
            for (locale in listOf(Locale.ENGLISH, Locale.getDefault())) {
                val name = Locale("", code).getDisplayCountry(locale)
                if (name.isNotBlank()) out += fold(name)
            }
        }
        out += listOf(
            "uk", "usa", "uae", "ksa", "tur", "turkiye", "czech republic", "north macedonia",
            "macedonia", "ivory coast", "cote d'ivoire", "south korea", "north korea", "russia",
            "vietnam", "laos", "brunei", "bolivia", "venezuela", "iran", "moldova", "tanzania",
            "vatican", "palestine", "syria",
            "gbr", "deu", "fra", "ita", "esp", "prt", "nld", "bel", "che", "aut", "swe",
            "nor", "dnk", "fin", "pol", "cze", "svk", "hun", "rou", "bgr", "grc", "rus",
            "ukr", "srb", "hrv", "svn", "bih", "mkd", "alb", "mne", "arg", "bra", "chl",
            "col", "per", "ven", "ecu", "bol", "pry", "ury", "mex", "gtm", "cri", "pan",
            "dom", "pri", "cub", "jam", "tto", "hti", "slv", "hnd", "nic", "isr", "sau",
            "are", "qat", "kwt", "bhr", "omn", "jor", "lbn", "syr", "irq", "irn", "pak",
            "ind", "bgd", "lka", "npl", "tha", "vnm", "phl", "mys", "sgp", "idn", "chn",
            "jpn", "kor", "twn", "hkg", "aus", "nzl", "zaf", "nga", "ken", "gha", "egy",
            "mar", "dza", "tun", "lby", "eth", "tza", "uga", "zmb", "zwe"
        ).mapTo(out) { fold(it) }
        out
    }
}
