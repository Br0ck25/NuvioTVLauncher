package com.nuvio.tv.music

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis

/**
 * Track/artist helpers shared by the Music curator, the listening history and the
 * recommendation UI. Ported from the desktop MusicRecommendations.kt so both apps
 * agree on what is a real song, what is a variant, and when two uploads are the
 * same recording.
 */
internal object MusicFilters {
    /**
     * True for a video the music curator should never recommend: covers, karaoke,
     * sped-up/nightcore edits, lessons, compilations and the like.
     */
    fun isVariant(title: String): Boolean =
        variantWords.any { containsWord(title, it) }

    /** Interviews, reactions, podcasts, tutorials and other non-music uploads. */
    fun looksLikeTalk(title: String): Boolean =
        talkWords.any { containsWord(title, it) }

    /** Whole-word match, so "Discovery" is not a cover and "Preview" is not a review. */
    private fun containsWord(text: String, word: String): Boolean =
        Regex(
            "(^|[^\\p{L}\\p{N}])${Regex.escape(word)}([^\\p{L}\\p{N}]|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

    /**
     * True when two results are the same song under different uploads. Artist names
     * are deliberately not part of this: lyric channels and re-uploads credit
     * different authors. Single-word titles fall back to artist + title.
     */
    fun sameSong(left: Song, right: Song): Boolean {
        val a = significantTitleTokens(left.title)
        val b = significantTitleTokens(right.title)
        if (a.size < 2 || b.size < 2) return songKey(left) == songKey(right)
        val shared = a.count { it in b }
        return shared.toDouble() / minOf(a.size, b.size) >= 0.6
    }

    fun songKey(song: Song): String {
        val tokens = significantTitleTokens(song.title)
        val titleKey = if (tokens.isEmpty()) {
            song.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        } else {
            tokens.sorted().joinToString(" ")
        }
        if (titleKey.isBlank()) return ""
        val artist = normalizeArtistName(song.artist)
        return if (tokens.size >= 2) titleKey else "$artist|$titleKey"
    }

    /** Title words that identify the song, ignoring decorations and stop words. */
    fun significantTitleTokens(title: String): Set<String> =
        title.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.length >= 3 && it !in titleStopWords }
            .toSet()

    /** A YouTube Music/catalogue track; local files cannot seed a YouTube radio. */
    fun Song.isYouTubeTrack(): Boolean =
        videoId.isNotBlank() &&
            localUri == null &&
            !videoId.startsWith("local:") &&
            !videoId.startsWith("webdav:") &&
            !videoId.startsWith("navidrome:")

    /**
     * A track the curator may put on a personal shelf. Live/Shorts are not in the
     * TV model, so the duration and title checks carry that weight here.
     */
    fun Song.isUsable(): Boolean {
        if (!isYouTubeTrack()) return false
        if (isVariant(title) || looksLikeTalk(title)) return false
        val duration = durationMillis()
        return duration == 0L || duration in 45_000L..900_000L
    }

    fun cleanArtistName(author: String?): String {
        var value = author.orEmpty().substringBefore(" • ").trim()
        if (value.isBlank()) return ""
        if (value.endsWith("VEVO", ignoreCase = true) && value.length > 4) {
            value = value.dropLast(4).trim()
        }
        var changed = true
        while (changed) {
            changed = false
            for (suffix in artistSuffixes) {
                if (value.endsWith(suffix)) {
                    value = value.removeSuffix(suffix).trim()
                    changed = true
                }
            }
        }
        return value.ifBlank { author.orEmpty().substringBefore(" • ").trim() }
    }

    /** Artist matching key: lowercase, channel suffixes removed, punctuation folded. */
    fun normalizeArtistName(author: String?): String {
        var value = author.orEmpty().substringBefore(" • ").lowercase().trim()
        if (value.isBlank()) return ""
        value = value.removeSuffix(" - topic").removeSuffix(" topic").trim()
        if (value.endsWith("vevo")) value = value.removeSuffix("vevo").trim()
        value = value.removeSuffix(" official")
            .removeSuffix(" official audio")
            .removeSuffix(" official video")
            .trim()
        value = value.replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (value in ignoredArtists) "" else value
    }

    /** Splits a byline like "A, B & C feat. D" into separate artist names. */
    fun splitArtistNames(byline: String?): List<String> {
        val clean = byline?.substringBefore(" • ")?.trim().orEmpty()
        if (clean.isBlank()) return emptyList()
        return clean.split(Regex("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\band\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Removes the "(Official Video)" and "Artist - " decoration from a song title. */
    fun cleanSongTitle(title: String, artist: String?): String {
        var text = title
        if (artist != null) {
            val dash = Regex("^\\s*(.+?)\\s+[-–—]\\s+(.+)$").find(text)
            if (dash != null) {
                val left = normalizeArtistName(dash.groupValues[1])
                val wanted = normalizeArtistName(artist)
                if (left.contains(wanted) || wanted.contains(left)) {
                    text = dash.groupValues[2]
                }
            }
        }
        text = text.replace(Regex("[\\[(][^)\\]]*[)\\]]"), " ")
        text = text.replace(Regex("\\s+(?:ft\\.?|feat\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("[\"'’‘“”`]"), "")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private val variantWords = listOf(
        "cover", "karaoke", "instrumental", "nightcore", "slowed", "reverb",
        "8d audio", "sped up", "tribute", "made famous", "in the style of",
        "backing track", "live at", "live from", "live on", "remix",
        "choreography", "dance studio", "guitar lesson", "how to play",
        "tiktok", "fortnite", "mashup", "nonstop", "medley", "compilation",
        "party mix", "mega mix", "dj mix", "playlist"
    )

    private val talkWords = listOf(
        "interview", "reaction", "podcast", "behind the scenes", "making of",
        "tutorial", "unboxing", "review", "trailer", "teaser", "performs",
        "late late show", "homefest", "choreography", "dance studio",
        "guitar lesson", "how to play"
    )

    private val artistSuffixes = listOf(
        " - Topic", " - topic", " VEVO", " vevo", " Official", " official",
        " Official Audio", " Official Video", " - Official"
    )

    private val ignoredArtists = setOf(
        "unknown artist", "music", "youtube music", "various artists", "vevo"
    )

    private val titleStopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "from",
        "official", "video", "audio", "lyrics", "lyric", "music", "feat", "ft",
        "remix", "version", "edit", "hd", "hq", "visualizer", "visualiser",
        "topic", "live", "performance", "full", "album", "explicit", "remaster",
        "remastered", "legendado", "traducao", "tradução", "clipe", "oficial"
    )
}
