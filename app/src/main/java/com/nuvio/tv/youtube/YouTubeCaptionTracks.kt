package com.nuvio.tv.youtube

import java.util.Locale

/**
 * YouTube's generated DASH manifest carries an auto-translated caption track for every language
 * it can translate to, often 150 or more. ExoPlayer turns each one into a text track, and the TV
 * player walks the whole list every time the tracks change. On a 1 GB box that shows up as a
 * subtitle-related CPU spike while a video starts or changes.
 *
 * Only the captions the video really ships (written tracks and the automatic track in the spoken
 * language) and the auto-translations the viewer is likely to use are kept: the subtitle
 * preference, the device languages, and English as the usual fallback.
 */
internal object YouTubeCaptionTracks {
    private const val FALLBACK_LANGUAGE = "en"

    /** One `<AdaptationSet>` per caption track, as the engine's manifest builder writes them. */
    private val adaptationSet = Regex(
        "<AdaptationSet\\b[^>]*>.*?</AdaptationSet>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val timedTextUrl = Regex("<BaseURL>([^<]*timedtext[^<]*)</BaseURL>")
    private val translatedLanguage = Regex("[?&]tlang=([^&]+)")

    /**
     * The base language codes worth keeping. Regional variants share a base code, so keeping
     * "pt" also keeps "pt-BR" and "pt-PT"; at most a couple of extra tracks, never the full list.
     */
    fun keepLanguages(
        preferred: String?,
        secondary: String?,
        device: List<String> = deviceLanguages()
    ): Set<String> {
        val keep = LinkedHashSet<String>()
        device.forEach { normalize(it)?.let(keep::add) }
        normalize(FALLBACK_LANGUAGE)?.let(keep::add)
        normalize(preferred)?.let(keep::add)
        normalize(secondary)?.let(keep::add)
        return keep
    }

    /**
     * Removes translated caption sets whose language is not in [keep]. A set with a real caption
     * track (no `tlang`) is never removed, and a set holding several tracks is kept if any one of
     * them is kept, so a mixed set cannot lose the original captions.
     */
    fun limitTranslated(mpd: String, keep: Set<String>): String {
        if (keep.isEmpty()) return mpd

        var removed = 0
        var kept = 0
        val limited = adaptationSet.replace(mpd) { set ->
            val urls = timedTextUrl.findAll(set.value)
                .map { it.groupValues[1].replace("&amp;", "&") }
                .toList()
            // Video and audio sets have no timedtext URL; leave them completely alone.
            if (urls.isEmpty()) return@replace set.value
            val translations = urls.mapNotNull { url ->
                translatedLanguage.find(url)?.groupValues?.get(1)?.let(::normalize)
            }
            val translatedOnly = translations.isNotEmpty() && translations.size == urls.size
            if (translatedOnly && translations.none { it in keep }) {
                removed++
                ""
            } else {
                kept++
                set.value
            }
        }

        if (removed > 0 || kept > 0) {
            android.util.Log.i(
                "YouTubeCaptions",
                "caption tracks kept=$kept removed=$removed languages=$keep"
            )
        }
        return limited
    }

    private fun deviceLanguages(): List<String> =
        runCatching {
            val locales = android.os.LocaleList.getDefault()
            (0 until locales.size()).map { locales[it].language }
        }.getOrDefault(listOf(Locale.getDefault().language))

    /** "tr-TR", "TR" and "tr_TR" all become "tr"; special settings are not languages. */
    private fun normalize(raw: String?): String? {
        val code = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        if (code == "none" || code == "forced" || code == "device") return null
        return code.substringBefore('-').substringBefore('_')
    }
}
