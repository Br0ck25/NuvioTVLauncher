package com.nuvio.tv.youtube

import android.content.Context

/** Your YouTube choices, kept on this device. */
object YouTubeSettings {
    private const val PREFS = "youtube_settings"
    private const val KEY_SPONSORBLOCK = "sponsorblock_enabled"
    private const val KEY_CATEGORIES = "sponsorblock_categories"
    private const val KEY_QUALITY = "preferred_height"
    private const val KEY_HIDDEN_TABS = "hidden_tabs"
    private const val KEY_AUTOPLAY = "autoplay_next"
    private const val KEY_HIDE_SHORTS = "hide_shorts"
    private const val KEY_HIDDEN_WORDS = "hidden_keywords"
    private const val KEY_HIDDEN_CHANNELS = "hidden_channels"
    private const val KEY_LAST_TAB = "last_tab"
    private const val KEY_TAB_ORDER = "tab_order"
    private const val KEY_REMEMBER_TAB = "remember_last_tab"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_VOLUME_OFFSET_DB = "volume_offset_db"
    private const val KEY_SPONSOR_NOTICE = "sponsorblock_notice"
    private const val KEY_SPONSOR_ASK = "sponsorblock_ask"
    private const val KEY_DEARROW_TITLES = "dearrow_titles"
    private const val KEY_DEARROW_THUMBS = "dearrow_thumbnails"
    private const val KEY_HIDE_WATCHED = "hide_watched"
    private const val KEY_HIDE_UPCOMING = "hide_upcoming"
    private const val KEY_CODEC = "codec_preference"
    private const val KEY_COMPATIBILITY = "compatibility_mode"
    private const val KEY_AUTO_SUBTITLES = "auto_subtitles"
    private const val KEY_VIDEO_PREVIEWS = "video_previews"
    private const val KEY_CONTENT_REGION = "content_region"
    private const val KEY_POSTER_COLUMNS = "poster_columns"
    private const val KEY_SHOW_CLOCK = "show_clock"
    private const val KEY_CHAT_POSITION = "chat_position"
    private const val KEY_CHAT_SIZE = "chat_size"
    private const val KEY_CHAT_TEXT_COLOR = "chat_text_color"
    private const val KEY_CHAT_NAME_COLOR = "chat_name_color"
    private const val KEY_CHAT_BACKGROUND = "chat_background_opacity"
    private const val KEY_PINNED_PLAYLISTS = "pinned_playlists"

    fun videoPreviews(context: Context) = prefs(context).getBoolean(KEY_VIDEO_PREVIEWS, true)
    fun setVideoPreviews(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_VIDEO_PREVIEWS, on).apply()

    fun autoSubtitles(context: Context) = prefs(context).getBoolean(KEY_AUTO_SUBTITLES, true)
    fun setAutoSubtitles(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_SUBTITLES, on).apply()

    /** The country whose videos show in News and Live; blank follows the device region. */
    fun contentRegion(context: Context): String = prefs(context).getString(KEY_CONTENT_REGION, "") ?: ""
    fun setContentRegion(context: Context, region: String) =
        prefs(context).edit().putString(KEY_CONTENT_REGION, region.trim().uppercase()).apply()

    /** How many posters fit across a row in the YouTube grids (3 to 5). */
    fun posterColumns(context: Context): Int =
        prefs(context).getInt(KEY_POSTER_COLUMNS, 5).coerceIn(3, 5)
    fun setPosterColumns(context: Context, columns: Int) =
        prefs(context).edit().putInt(KEY_POSTER_COLUMNS, columns.coerceIn(3, 5)).apply()

    /** The time in the middle of the YouTube header. */
    fun showClock(context: Context) = prefs(context).getBoolean(KEY_SHOW_CLOCK, true)
    fun setShowClock(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_SHOW_CLOCK, on).apply()

    /** Where the live chat sits: top/center/bottom combined with start/center/end. */
    fun chatPosition(context: Context): String =
        prefs(context).getString(KEY_CHAT_POSITION, "center_end") ?: "center_end"
    fun setChatPosition(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CHAT_POSITION, value).apply()

    /** small, medium or large. */
    fun chatSize(context: Context): String =
        prefs(context).getString(KEY_CHAT_SIZE, "medium") ?: "medium"
    fun setChatSize(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CHAT_SIZE, value).apply()

    fun chatTextColor(context: Context): Int =
        prefs(context).getInt(KEY_CHAT_TEXT_COLOR, 0xFFFFFFFF.toInt())
    fun setChatTextColor(context: Context, color: Int) =
        prefs(context).edit().putInt(KEY_CHAT_TEXT_COLOR, color).apply()

    /** Names keep their own colour so they stay distinct from the message text. */
    fun chatNameColor(context: Context): Int =
        prefs(context).getInt(KEY_CHAT_NAME_COLOR, 0xFF9BB8FF.toInt())
    fun setChatNameColor(context: Context, color: Int) =
        prefs(context).edit().putInt(KEY_CHAT_NAME_COLOR, color).apply()

    /** 0 to 100: how much of the chat window's background is drawn. */
    fun chatBackgroundOpacity(context: Context): Int =
        prefs(context).getInt(KEY_CHAT_BACKGROUND, 60)
    fun setChatBackgroundOpacity(context: Context, percent: Int) =
        prefs(context).edit().putInt(KEY_CHAT_BACKGROUND, percent.coerceIn(0, 100)).apply()

    /** SponsorBlock's segment types, in the order they're listed. */
    val sponsorCategories: List<Pair<String, String>> = listOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Self-promotion",
        "interaction" to "Like and subscribe reminders",
        "intro" to "Intros",
        "outro" to "Outros and end cards",
        "preview" to "Previews and recaps",
        "music_offtopic" to "Non-music parts",
        "filler" to "Filler"
    )

    private val defaultCategories = setOf("sponsor", "selfpromo", "interaction")

    /** The sizes you can set as a default; 0 is automatic. */
    val qualityChoices = listOf(0, 2160, 1440, 1080, 720, 480, 360)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sponsorBlockEnabled(context: Context) = prefs(context).getBoolean(KEY_SPONSORBLOCK, true)

    fun setSponsorBlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPONSORBLOCK, enabled).apply()
    }

    fun sponsorCategoriesOn(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CATEGORIES, null)?.toSet() ?: defaultCategories

    fun setSponsorCategory(context: Context, category: String, on: Boolean) {
        val next = sponsorCategoriesOn(context).toMutableSet().apply { if (on) add(category) else remove(category) }
        prefs(context).edit().putStringSet(KEY_CATEGORIES, next).apply()
    }

    /** Names of the tabs you've turned off on the YouTube page. */
    fun hiddenTabs(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN_TABS, null)?.toSet() ?: emptySet()

    fun setTabVisible(context: Context, tab: YtTab, visible: Boolean) {
        val next = hiddenTabs(context).toMutableSet().apply { if (visible) remove(tab.name) else add(tab.name) }
        prefs(context).edit().putStringSet(KEY_HIDDEN_TABS, next).apply()
    }

    /** Every tab in the order you've set. Tabs added in a later version go on the end. */
    fun tabOrder(context: Context): List<YtTab> {
        val saved = prefs(context).getString(KEY_TAB_ORDER, null)
            ?.split(',')
            ?.mapNotNull { name -> YtTab.entries.firstOrNull { it.name == name } }
            .orEmpty()
            .distinct()
        return saved + YtTab.entries.filter { it !in saved }
    }

    fun setTabOrder(context: Context, order: List<YtTab>) =
        prefs(context).edit().putString(KEY_TAB_ORDER, order.joinToString(",") { it.name }).apply()

    /** The tabs to show, in your order. There is always at least one. */
    fun visibleTabs(context: Context): List<YtTab> {
        val hidden = hiddenTabs(context)
        return tabOrder(context).filter { it.name !in hidden }.ifEmpty { listOf(YtTab.HOME) }
    }

    /** The tab you were last on, if it's still switched on; otherwise the first one. */
    fun lastTab(context: Context): YtTab {
        val visible = visibleTabs(context)
        if (!rememberLastTab(context)) return visible.first()
        val saved = prefs(context).getString(KEY_LAST_TAB, null)
        return visible.firstOrNull { it.name == saved } ?: visible.first()
    }

    /** Open on the tab you were last on; off means the first tab each time. */
    fun rememberLastTab(context: Context) = prefs(context).getBoolean(KEY_REMEMBER_TAB, true)
    fun setRememberLastTab(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_REMEMBER_TAB, on).apply()

    /** The playback speed you last picked on a YouTube video, used to start the next one. */
    fun playbackSpeed(context: Context) = prefs(context).getFloat(KEY_SPEED, 1f)
    fun setPlaybackSpeed(context: Context, speed: Float) = prefs(context).edit().putFloat(KEY_SPEED, speed).apply()

    /** How much louder or quieter OneTube sounds than the rest of the app, in dB. */
    val volumeOffsetChoices = listOf(0, -3, -6, -9, -12, 3, 6)

    fun volumeOffsetDb(context: Context): Int =
        prefs(context).getInt(KEY_VOLUME_OFFSET_DB, 0).coerceIn(-12, 6)

    fun setVolumeOffsetDb(context: Context, db: Int) =
        prefs(context).edit().putInt(KEY_VOLUME_OFFSET_DB, db.coerceIn(-12, 6)).apply()

    fun volumeOffsetLabel(db: Int): String = when {
        db > 0 -> "+$db dB"
        else -> "$db dB"
    }

    fun setLastTab(context: Context, tab: YtTab) = prefs(context).edit().putString(KEY_LAST_TAB, tab.name).apply()

    /** Ask before skipping a SponsorBlock segment, instead of skipping it straight away. */
    fun sponsorAsk(context: Context) = prefs(context).getBoolean(KEY_SPONSOR_ASK, false)
    fun setSponsorAsk(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SPONSOR_ASK, on).apply()

    /** Whether skipping a SponsorBlock segment shows a short notice. */
    fun sponsorNotice(context: Context) = prefs(context).getBoolean(KEY_SPONSOR_NOTICE, true)
    fun setSponsorNotice(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SPONSOR_NOTICE, on).apply()

    /** DeArrow: community titles and thumbnails in place of clickbait. */
    fun deArrowTitles(context: Context) = prefs(context).getBoolean(KEY_DEARROW_TITLES, true)
    fun deArrowThumbnails(context: Context) = prefs(context).getBoolean(KEY_DEARROW_THUMBS, true)
    fun setDeArrowTitles(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_DEARROW_TITLES, on).apply()
    fun setDeArrowThumbnails(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_DEARROW_THUMBS, on).apply()

    /** Words that keep a video off the page when they're in its title or channel name. Lower case. */
    fun hiddenKeywords(context: Context): List<String> =
        (prefs(context).getStringSet(KEY_HIDDEN_WORDS, null) ?: emptySet()).sorted()

    fun addHiddenKeyword(context: Context, word: String) {
        val clean = word.trim().lowercase()
        if (clean.isBlank()) return
        prefs(context).edit().putStringSet(KEY_HIDDEN_WORDS, (hiddenKeywords(context) + clean).toSet()).apply()
    }

    fun removeHiddenKeyword(context: Context, word: String) {
        prefs(context).edit().putStringSet(KEY_HIDDEN_WORDS, (hiddenKeywords(context) - word).toSet()).apply()
    }

    /** Channels you've hidden, as (id, name). */
    fun hiddenChannels(context: Context): List<Pair<String, String>> =
        (prefs(context).getStringSet(KEY_HIDDEN_CHANNELS, null) ?: emptySet())
            .mapNotNull { entry -> entry.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .sortedBy { it.second.lowercase() }

    fun hideChannel(context: Context, id: String, name: String) {
        val kept = hiddenChannels(context).filter { it.first != id } + (id to name)
        prefs(context).edit().putStringSet(KEY_HIDDEN_CHANNELS, kept.map { "${it.first}|${it.second}" }.toSet()).apply()
    }

    fun unhideChannel(context: Context, id: String) {
        val kept = hiddenChannels(context).filter { it.first != id }
        prefs(context).edit().putStringSet(KEY_HIDDEN_CHANNELS, kept.map { "${it.first}|${it.second}" }.toSet()).apply()
    }

    /** Playlists you've pinned to the top of the YouTube page, as id to title. */
    fun pinnedPlaylists(context: Context): List<Pair<String, String>> =
        (prefs(context).getStringSet(KEY_PINNED_PLAYLISTS, null) ?: emptySet())
            .mapNotNull { entry ->
                entry.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }
            .sortedBy { it.second.lowercase() }

    fun pinPlaylist(context: Context, id: String, title: String) {
        val kept = pinnedPlaylists(context).filter { it.first != id } + (id to title)
        prefs(context).edit()
            .putStringSet(KEY_PINNED_PLAYLISTS, kept.map { "${it.first}|${it.second}" }.toSet())
            .apply()
    }

    fun unpinPlaylist(context: Context, id: String) {
        val kept = pinnedPlaylists(context).filter { it.first != id }
        prefs(context).edit()
            .putStringSet(KEY_PINNED_PLAYLISTS, kept.map { "${it.first}|${it.second}" }.toSet())
            .apply()
    }

    /** Feed filters. Upcoming premieres can't be played yet, so they're hidden unless you say otherwise. */
    fun hideShorts(context: Context) = prefs(context).getBoolean(KEY_HIDE_SHORTS, false)
    fun hideWatched(context: Context) = prefs(context).getBoolean(KEY_HIDE_WATCHED, false)
    fun hideUpcoming(context: Context) = prefs(context).getBoolean(KEY_HIDE_UPCOMING, true)

    fun setHideShorts(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HIDE_SHORTS, on).apply()
    fun setHideWatched(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HIDE_WATCHED, on).apply()
    fun setHideUpcoming(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HIDE_UPCOMING, on).apply()

    /** Play the next video when one ends. */
    fun autoplayNext(context: Context) = prefs(context).getBoolean(KEY_AUTOPLAY, true)

    fun setAutoplayNext(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY, on).apply()
    }

    /** Which video format to prefer when YouTube offers the same size in several. */
    val codecChoices = listOf("auto", "avc", "vp9", "av1")

    fun codecPreference(context: Context): String =
        prefs(context).getString(KEY_CODEC, "auto")?.takeIf { it in codecChoices } ?: "auto"

    fun setCodecPreference(context: Context, codec: String) {
        prefs(context).edit().putString(KEY_CODEC, codec).apply()
    }

    fun codecLabel(codec: String) = when (codec) {
        "avc" -> "H.264 (most compatible)"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        else -> "Auto"
    }

    /** Video MIME types in the order to try them. Auto leaves AV1 for last: few boxes decode it well. */
    fun codecMimeOrder(context: Context): List<String> {
        val vp9 = "video/x-vnd.on2.vp9"
        val avc = "video/avc"
        val av1 = "video/av01"
        return when (codecPreference(context)) {
            "avc" -> listOf(avc, vp9, av1)
            "vp9" -> listOf(vp9, avc, av1)
            "av1" -> listOf(av1, vp9, avc)
            else -> if (compatibilityActive(context)) listOf(avc, vp9, av1) else listOf(vp9, avc, av1)
        }
    }

    /** The video height to start at; 0 lets the player choose. */
    fun preferredHeight(context: Context) = prefs(context).getInt(KEY_QUALITY, 0)

    fun setPreferredHeight(context: Context, height: Int) {
        prefs(context).edit().putInt(KEY_QUALITY, height).apply()
    }

    fun qualityLabel(height: Int) = if (height <= 0) "Auto" else "${height}p"

    /**
     * The order the settings row cycles through. On a device using compatibility, the safe
     * capped size comes first; the higher sizes stay available but are a deliberate later choice.
     */
    fun qualityCycle(context: Context): List<Int> {
        if (!compatibilityActive(context)) return qualityChoices
        val cap = compatibilityMaxHeight(context)
        val lower = qualityChoices.filter { it in 1 until cap }.sortedDescending()
        val higher = qualityChoices.filter { it > cap }.sorted()
        return listOf(0, cap) + lower + higher
    }

    // ------------------------------------------------------------ device compatibility

    /** Auto lets the detected device profile decide; On and Off force it. */
    fun compatibilityMode(context: Context): String =
        prefs(context).getString(KEY_COMPATIBILITY, COMPATIBILITY_AUTO)
            ?.takeIf { it in setOf(COMPATIBILITY_AUTO, COMPATIBILITY_ON, COMPATIBILITY_OFF) }
            ?: COMPATIBILITY_AUTO

    fun setCompatibilityMode(context: Context, mode: String) {
        val safe = mode.takeIf { it in setOf(COMPATIBILITY_AUTO, COMPATIBILITY_ON, COMPATIBILITY_OFF) }
            ?: COMPATIBILITY_AUTO
        prefs(context).edit().putString(KEY_COMPATIBILITY, safe).apply()
    }

    fun compatibilityActive(context: Context): Boolean = when (compatibilityMode(context)) {
        COMPATIBILITY_ON -> true
        COMPATIBILITY_OFF -> false
        else -> YouTubeCompatibility.deviceProfile(context).needsCompatibility
    }

    fun compatibilityMaxHeight(context: Context): Int =
        YouTubeCompatibility.deviceProfile(context).compatibilityHeight

    fun compatibilityLabel(context: Context, mode: String = compatibilityMode(context)): String = when (mode) {
        COMPATIBILITY_ON -> "On"
        COMPATIBILITY_OFF -> "Off"
        else -> if (compatibilityActive(context)) "Auto (${compatibilityMaxHeight(context)}p)" else "Auto"
    }

    fun compatibilityDescription(context: Context, mode: String = compatibilityMode(context)): String = when (mode) {
        COMPATIBILITY_ON ->
            "Always prefer H.264 and cap the starting quality for smoother playback."
        COMPATIBILITY_OFF ->
            "Full codec and quality selection. Pick Automatic to let this device choose again."
        else -> {
            val profile = YouTubeCompatibility.deviceProfile(context)
            if (profile.needsCompatibility) {
                "This device starts with H.264 and ${profile.compatibilityHeight}p (${profile.reason.lowercase()})."
            } else {
                "This device can use its best codecs and resolution."
            }
        }
    }

    /**
     * The size the player should start at. An explicit choice always wins; Auto follows the
     * compatibility profile.
     */
    fun effectivePreferredHeight(context: Context): Int {
        val saved = preferredHeight(context)
        val mode = compatibilityMode(context)
        // A size the viewer picked explicitly always wins. The compatibility cap only decides
        // what "Auto" means for this device, unless compatibility is forced on.
        if (saved > 0) {
            return if (mode == COMPATIBILITY_ON && saved > compatibilityMaxHeight(context)) {
                compatibilityMaxHeight(context)
            } else {
                saved
            }
        }
        return if (mode == COMPATIBILITY_ON || (mode == COMPATIBILITY_AUTO && compatibilityActive(context))) {
            compatibilityMaxHeight(context)
        } else {
            0
        }
    }

    /**
     * Picking a size above the compatibility cap is an explicit request for the full quality
     * path, so compatibility is switched off for this device from then on.
     */
    fun onUserSelectedQuality(context: Context, height: Int) {
        if (height == 0) {
            if (compatibilityMode(context) == COMPATIBILITY_OFF) setCompatibilityMode(context, COMPATIBILITY_AUTO)
            return
        }
        if (height > compatibilityMaxHeight(context) && compatibilityActive(context)) {
            setCompatibilityMode(context, COMPATIBILITY_OFF)
        }
    }

    /** Choosing VP9/AV1 explicitly also means the user wants the full codec path. */
    fun onUserSelectedCodec(context: Context, codec: String) {
        when (codec) {
            "vp9", "av1" -> if (compatibilityActive(context)) {
                setCompatibilityMode(context, COMPATIBILITY_OFF)
            }
            "auto" -> if (compatibilityMode(context) == COMPATIBILITY_OFF) {
                setCompatibilityMode(context, COMPATIBILITY_AUTO)
            }
        }
    }

    private const val COMPATIBILITY_AUTO = "auto"
    private const val COMPATIBILITY_ON = "on"
    private const val COMPATIBILITY_OFF = "off"
}
