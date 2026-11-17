package com.nuvio.tv.livetv.model

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

    /** Every distinct tag in [channels], with how many channels and groups use it. */
    fun scan(channels: List<LiveChannel>): List<Tag> {
        val labels = HashMap<String, String>()
        val channelKeys = HashMap<String, MutableSet<String>>()
        val groupIds = HashMap<String, MutableSet<String>>()
        channels.forEach { channel ->
            rawTokens(channel.group).forEach { raw ->
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

    fun normalize(value: String): String =
        value.trim().lowercase().replace(whitespace, " ")

    private fun isUseful(key: String): Boolean =
        key.length in 2..40 && key.any { it.isLetter() } && key !in ignored

    private val separators = Regex("[|:•/\\\\\\-–—()\\[\\]{},;]+")
    private val words = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    /** Quality/status words that are never useful as a language or country filter. */
    private val ignored = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "vip", "backup", "test", "ppv"
    )
}
