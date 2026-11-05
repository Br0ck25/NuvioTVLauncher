package com.nuvio.tv.core.mylists

import com.nuvio.tv.core.apps.INSTALLED_APPS_CATALOG_KEY
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonBehaviorHints
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import java.net.URLEncoder

object MyListsDefaultSeed {
    const val MY_LISTS_ADDON_ID = "app.my-list"
    const val MY_LISTS_COLLECTIONS_ADDON_ID = "app.my-list.collections"
    const val MY_LISTS_ADDON_NAME = "My Lists"
    const val MY_LISTS_ADDON_VERSION = "2.1.0"
    const val MY_LISTS_BASE_ORIGIN = "https://mylistsaddon.com"
    const val MY_LISTS_DEFAULT_BASE_URL = "$MY_LISTS_BASE_ORIGIN/nuvio-default"
    const val MY_LISTS_DEFAULT_MANIFEST_URL = "$MY_LISTS_DEFAULT_BASE_URL/manifest.json"
    const val MY_LISTS_DEFAULT_SYNC_URL = "$MY_LISTS_BASE_ORIGIN/api/nuvio-tv/sync/default"
    const val DEFAULT_TARGET_ID = "default"
    const val MY_LISTS_DEFAULT_COLLECTIONS_ASSET = "my_lists_default_collections.json"

    const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
    const val OPENSUBTITLES_BASE_URL = "https://opensubtitles-v3.strem.io"
    const val CINEMETA_ADDON_ID = "com.linvo.cinemeta"

    val DEFAULT_COLLECTION_IDS: List<String> = listOf(
        "col_mylists_streaming_services"
    )

    const val REMOVED_CATALOG_ID = "chart:trakt:boxoffice_tv"

    fun isRemovedCatalogKey(key: String): Boolean {
        return key.contains(REMOVED_CATALOG_ID, ignoreCase = true) ||
            (key.contains("boxoffice", ignoreCase = true) && key.contains("series", ignoreCase = true))
    }

    fun isRemovedCatalog(catalogId: String, type: String = ""): Boolean {
        return catalogId.equals(REMOVED_CATALOG_ID, ignoreCase = true) ||
            (catalogId.contains("boxoffice", ignoreCase = true) && type.equals("series", ignoreCase = true))
    }

    val TRAKT_BOXOFFICE_SERIES_CATALOG_KEYS: List<String> = listOf(
        "app.my-list_series_chart:trakt:boxoffice_tv",
        "https://mylistsaddon.com/nuvio-default_series_chart:trakt:boxoffice_tv_Trakt Box Office",
        "https://mylistsaddon.com/nuvio-default/_series_chart:trakt:boxoffice_tv_Trakt Box Office"
    )

    val REMOVED_COLLECTION_IDS: Set<String> = setOf(
        "col_mylists_genres",
        "col_mylists_franchises",
        "col_mylists_studios"
    )

    val STREAMING_SERVICE_COVER_IMAGES: Map<String, String> = mapOf(
        "col_appletv" to "https://i.ytimg.com/vi/6sX_BfDnyrg/maxresdefault.jpg",
        "col_discovery" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTFcanySRcnirlfDb5fXXxIYA2A31kLoHJ6DCj1sf4_C0xcQg_wzJjwU_7N&s=10",
        "col_disney" to "https://static-www.adweek.com/wp-content/uploads/2024/03/disney-logo.jpeg?w=1200",
        "col_hbomax" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTRInZVxLCd8CpYk_A80vU10wjypKNN_b1JUmTdhDj19ZlVJ2Ecz3nauXpN&s=10",
        "col_hulu" to "https://www.thisishulu.com/app/uploads/2023/10/logo-gradient-3up.svg",
        "col_netflix" to "https://m.media-amazon.com/images/I/616QXs8yg0L.png",
        "col_netflixkids" to "https://static1.squarespace.com/static/545ad6c5e4b09bbf6a29c5ee/t/5e69c67b1ce5f056432139df/1583990409924/Netflix.jpg?format=1500w",
        "col_paramount" to "https://cdn.mos.cms.futurecdn.net/UFo74BuGo7FYxhAE3DrWUP.jpg",
        "col_primevideo" to "https://oyster.ignimgs.com/mediawiki/apis.ign.com/prime-video/3/3b/Primevideo-seo-logo_s586.png",
        "col_peacock" to "https://www.newscaststudio.com/wp-content/uploads/2026/05/original-peacock-logo.jpg",
        "col_kids" to "https://t3.ftcdn.net/jpg/01/83/26/74/360_F_183267411_05WWsOOcByAaAjnfzklSLS530axmLTJB.jpg"
    )

    fun resolveCollectionFolderCoverImage(folderId: String, currentUrl: String?): String {
        val preset = STREAMING_SERVICE_COVER_IMAGES[folderId]
        val raw = currentUrl?.trim().orEmpty()
        if (!preset.isNullOrBlank() && (raw.isBlank() || raw.contains("api/channel-poster"))) {
            return preset
        }
        if (raw.isNotBlank()) {
            return when {
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
                raw.startsWith("/") -> "$MY_LISTS_BASE_ORIGIN$raw"
                else -> "$MY_LISTS_BASE_ORIGIN/$raw"
            }
        }
        return preset.orEmpty()
    }

    data class NetworkPresetArtwork(
        val logoUrl: String,
        val backdropUrl: String
    )

    val PRESET_NETWORK_ARTWORK: Map<String, NetworkPresetArtwork> = mapOf(
        "129" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FptSTdU4GPNJ1M8UVEOtA0KgtuNk.png", "https://image.tmdb.org/t/p/w780/kcl9SVzvtMJCGwx5PD1x04Ijnw7.jpg"),
        "2" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F2uy2ZWcplrSObIyt4x0Y9rkG6qO.png", "https://image.tmdb.org/t/p/w780/d7cH4zt3xoqRQnLqYXMPOrUz5k3.jpg"),
        "80" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FtHZPHOLc6iF27G34cAZGPsMtMSy.png", "https://image.tmdb.org/t/p/w780/5BDNWWHweQL0q1fmTv7gmRXfnl4.jpg"),
        "174" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FpmvRmATOCaDykE6JrVoeYxlFHw3.png", "https://image.tmdb.org/t/p/w780/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg"),
        "4" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FuJjcCg3O4DMEjM0xtno9OWFciRP.png", "https://image.tmdb.org/t/p/w780/vcFW09U4834DyFOeRZpsx9x1D3S.jpg"),
        "56" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fc5OC6oVCg6QP4eqzW6XIq17CQjI.png", "https://image.tmdb.org/t/p/w780/bIHCUqaapZdRjd2kzSDiwzYyrpC.jpg"),
        "16" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fwju8KhOUsR5y4bH9p3Jc50hhaLO.png", "https://image.tmdb.org/t/p/w780/8693rGsfkROQjiwlJD8O2WQV1Et.jpg"),
        "47" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F6ooPjtXufjsoskdJqj6pxuvHEno.png", "https://image.tmdb.org/t/p/w780/Ajku4m7jbMpCTEPr794Imv19mCA.jpg"),
        "64" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FtmttRFo2OiXQD0EHMxxlw8EzUuZ.png", "https://image.tmdb.org/t/p/w780/qdVdWLUAj9Xxrx0OIT76gWVI4fg.jpg"),
        "54" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F9AxUB1RdRnm0r5ki8Thb69Jo9Ma.png", "https://image.tmdb.org/t/p/w780/2UElp7Dkm3SiB9ZEdKEWNmAOijB.jpg"),
        "143" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Ftm2UclPxfamHyFuILLq4jNxsi8v.png", "https://image.tmdb.org/t/p/w780/t9cRI8CKYWlLFhdZF020UIpCim4.jpg"),
        "19" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F1DSpHrWyOORkL9N2QHX7Adt31mQ.png", "https://image.tmdb.org/t/p/w780/r0Q6eeN9L1ORL9QsV0Sg8ZV3vnv.jpg"),
        "88" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FaexGjtcs42DgRtZh7zOxayiry4J.png", "https://image.tmdb.org/t/p/w780/3gqDstdBLUbJ2jY3hl953iS97D4.jpg"),
        "384" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F9JTL7HcaiVxq7M6eu5m7giFqaxR.png", "https://image.tmdb.org/t/p/w780/eLNcBfr3212D09aYx0VChy1EpDY.jpg"),
        "49" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FtuomPhY2UtuPTqqFnKMVHvSb724.png", "https://image.tmdb.org/t/p/w780/lY2DhbA7Hy44fAKddr06UrXWWaQ.jpg"),
        "209" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F8SwN81R7P5vD5mhtOE0taw5mji4.png", "https://image.tmdb.org/t/p/w780/u64huzTnF3jEYUXJ4BDlpHy2L3K.jpg"),
        "65" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F9fGgdJz17aBX7dOyfHJtsozB7bf.png", "https://image.tmdb.org/t/p/w780/vBk9KQW01pJJfn3wAL2MJgTMch1.jpg"),
        "436" to NetworkPresetArtwork("https://image.tmdb.org/t/p/w500/y4fGncqELtnvRQuwSpuH8LncZqU.jpg", "https://image.tmdb.org/t/p/w780/c1aBrG5s5xFa6Tbnihu2Hhj4t2q.jpg"),
        "738" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FoEn2me5z7dMiuiyb5Fhq0NthIc2.png", "https://image.tmdb.org/t/p/w780/iWopHyAvuIDjGX10Yc3nn6UEebW.jpg"),
        "33" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fw4qtv7xBkSVsbOQdSzjUjlyOuSr.png", "https://image.tmdb.org/t/p/w780/gvrEWoL5LaljggThtnSLdueTY17.jpg"),
        "6" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fcm111bsDVlYaC1foL0itvEI4yLG.png", "https://image.tmdb.org/t/p/w780/xhK9YCxNwnE0OB1SWvCY39heHQI.jpg"),
        "13" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fi0e7qsKhCcB6EJy0qvjhnFB2lgp.png", "https://image.tmdb.org/t/p/w780/bauKfP3CX5JPAXpmveOlPG1pngC.jpg"),
        "149" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FfYi08k1rC5L0OOqgwyXfcAKCZ2H.png", "https://image.tmdb.org/t/p/w780/5FHVsxPLn1t8w9NEhcucuwSC3yp.jpg"),
        "68" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F65r0kR6MfOBYF0gEQsJGM6v5fEG.png", "https://image.tmdb.org/t/p/w780/veIrzS2AkYm0UaYYcVw9ziWkJoB.jpg"),
        "71" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2FhEpcdJ4O6eitG9ADSnDXNUrlovS.png", "https://image.tmdb.org/t/p/w780/lirPqYLTtd6XZqGn4cS1wiesTq0.jpg"),
        "84" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F6GRfZSrYh9D6C88n9kWlyrySB2l.png", "https://image.tmdb.org/t/p/w780/bpD0l55NTyHzIGX0QdTdogTk1ro.jpg"),
        "41" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2F6ISsKwa2XUhSC6oBtHZjYf6xFqv.png", "https://image.tmdb.org/t/p/w780/ymdCZck7hy7EDxUTR4Uq1MisfyX.jpg"),
        "30" to NetworkPresetArtwork("$MY_LISTS_BASE_ORIGIN/api/channel-logo?path=%2Fg1e0H0Ka97IG5SyInMXdJkHGKiH.png", "https://image.tmdb.org/t/p/w780/dMY7XUfEAK0Rl8hsgc8ufEOaiZ2.jpg")
    )

    private val QUICK_ADD_NETWORK_PRESETS: List<Pair<String, String>> = listOf(
        "129" to "A&E",
        "2" to "ABC",
        "80" to "Adult Swim",
        "174" to "AMC",
        "4" to "BBC One",
        "56" to "Cartoon Network",
        "16" to "CBS",
        "47" to "Comedy Central",
        "64" to "Discovery",
        "54" to "Disney Channel",
        "143" to "Food Network",
        "19" to "FOX",
        "88" to "FX",
        "384" to "Hallmark Channel",
        "49" to "HBO",
        "209" to "HGTV",
        "65" to "History",
        "436" to "Ion Television",
        "738" to "MeTV",
        "33" to "MTV",
        "6" to "NBC",
        "13" to "Nickelodeon",
        "149" to "Syfy",
        "68" to "TBS",
        "71" to "The CW",
        "84" to "TLC",
        "41" to "TNT",
        "30" to "USA Network"
    )

    val DEFAULT_QUICK_ADD_CHANNELS: List<MyListsTvChannel> = QUICK_ADD_NETWORK_PRESETS.mapIndexed { index, (netId, name) ->
        val artwork = PRESET_NETWORK_ARTWORK[netId]
        val encodedName = URLEncoder.encode(name, "UTF-8")
        MyListsTvChannel(
            id = "ch_preset_$netId",
            name = name,
            logoUrl = artwork?.logoUrl,
            posterUrl = "$MY_LISTS_BASE_ORIGIN/api/channel-poster?name=$encodedName&v=6",
            backdropUrl = artwork?.backdropUrl,
            category = "My Lists Addon Channels",
            catalogId = "channel_preset_$netId",
            playOrder = index + 1
        )
    }

    fun resolveChannelPosterUrl(channelName: String, fallbackUrl: String? = null): String {
        val clean = fallbackUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            return when {
                clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean.replace("https://prewarm.internal", MY_LISTS_BASE_ORIGIN)
                clean.startsWith("/") -> "$MY_LISTS_BASE_ORIGIN$clean"
                else -> "$MY_LISTS_BASE_ORIGIN/$clean"
            }
        }
        val encoded = URLEncoder.encode(channelName.trim().ifBlank { "Channel" }, "UTF-8")
        return "$MY_LISTS_BASE_ORIGIN/api/channel-poster?name=$encoded&v=6"
    }

    fun resolveChannelBackdropUrl(channelName: String, fallbackUrl: String? = null): String {
        val clean = fallbackUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            val resolved = when {
                clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean.replace("https://prewarm.internal", MY_LISTS_BASE_ORIGIN)
                clean.startsWith("/") -> "$MY_LISTS_BASE_ORIGIN$clean"
                else -> "$MY_LISTS_BASE_ORIGIN/$clean"
            }
            if (resolved.contains("/api/channel-poster") && !resolved.contains("format=landscape")) {
                return if (resolved.contains("?")) "$resolved&format=landscape" else "$resolved?format=landscape"
            }
            return resolved
        }
        val encoded = URLEncoder.encode(channelName.trim().ifBlank { "Channel" }, "UTF-8")
        return "$MY_LISTS_BASE_ORIGIN/api/channel-poster?name=$encoded&format=landscape&v=6"
    }

    fun resolveChannelLogoUrl(logoUrl: String?, channelName: String? = null): String? {
        val raw = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (raw == null) {
            val matchName = channelName?.trim()
            if (!matchName.isNullOrBlank()) {
                val matchedNetId = QUICK_ADD_NETWORK_PRESETS.firstOrNull { it.second.equals(matchName, ignoreCase = true) }?.first
                if (matchedNetId != null && PRESET_NETWORK_ARTWORK.containsKey(matchedNetId)) {
                    return PRESET_NETWORK_ARTWORK[matchedNetId]?.logoUrl
                }
            }
            return null
        }
        val fixed = raw.replace("https://prewarm.internal", MY_LISTS_BASE_ORIGIN)
        if (fixed.contains("netId=")) {
            val netId = Regex("""[?&]netId=(\d+)""").find(fixed)?.groupValues?.getOrNull(1)
            if (netId != null && PRESET_NETWORK_ARTWORK.containsKey(netId)) {
                return PRESET_NETWORK_ARTWORK[netId]?.logoUrl
            }
            if (!channelName.isNullOrBlank()) {
                return null
            }
        }
        return when {
            fixed.startsWith("http://", ignoreCase = true) || fixed.startsWith("https://", ignoreCase = true) -> fixed
            fixed.startsWith("/") -> "$MY_LISTS_BASE_ORIGIN$fixed"
            else -> "$MY_LISTS_BASE_ORIGIN/$fixed"
        }
    }

    val DEFAULT_CATALOG_SPECS: List<CatalogDescriptor> = listOf(
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "chart:combined:trending_movies",
            name = "Trending",
            extra = listOf(CatalogExtra(name = "skip", isRequired = false)),
            showInHome = true,
            hasExplicitShowInHome = true,
            extraSupported = listOf("skip")
        ),
        CatalogDescriptor(
            type = ContentType.SERIES,
            rawType = "series",
            id = "chart:combined:trending_tv",
            name = "Trending",
            extra = listOf(CatalogExtra(name = "skip", isRequired = false)),
            showInHome = true,
            hasExplicitShowInHome = true,
            extraSupported = listOf("skip")
        ),
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "chart:combined:popular_movies",
            name = "Popular",
            extra = listOf(CatalogExtra(name = "skip", isRequired = false)),
            showInHome = true,
            hasExplicitShowInHome = true,
            extraSupported = listOf("skip")
        ),
        CatalogDescriptor(
            type = ContentType.SERIES,
            rawType = "series",
            id = "chart:combined:popular_tv",
            name = "Popular",
            extra = listOf(CatalogExtra(name = "skip", isRequired = false)),
            showInHome = true,
            hasExplicitShowInHome = true,
            extraSupported = listOf("skip")
        ),
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "chart:trakt:boxoffice_movies",
            name = "Trakt Box Office",
            extra = listOf(CatalogExtra(name = "skip", isRequired = false)),
            showInHome = true,
            hasExplicitShowInHome = true,
            extraSupported = listOf("skip")
        )
    )

    private val SEARCH_CATALOG_SPECS: List<CatalogDescriptor> = listOf(
        CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "search_movies",
            name = "My Lists Search",
            extra = listOf(
                CatalogExtra(name = "search", isRequired = true),
                CatalogExtra(name = "skip", isRequired = false)
            ),
            showInHome = false,
            hasExplicitShowInHome = false,
            extraSupported = listOf("search", "skip"),
            extraRequired = listOf("search")
        ),
        CatalogDescriptor(
            type = ContentType.SERIES,
            rawType = "series",
            id = "search_series",
            name = "My Lists Search",
            extra = listOf(
                CatalogExtra(name = "search", isRequired = true),
                CatalogExtra(name = "skip", isRequired = false)
            ),
            showInHome = false,
            hasExplicitShowInHome = false,
            extraSupported = listOf("search", "skip"),
            extraRequired = listOf("search")
        )
    )

    val DEFAULT_HOME_CATALOG_KEYS: List<String> = DEFAULT_CATALOG_SPECS.map { descriptor ->
        homeCatalogKey(MY_LISTS_ADDON_ID, descriptor.apiType, descriptor.id)
    }

    val DEFAULT_HOME_COLLECTION_KEYS: List<String> = DEFAULT_COLLECTION_IDS.map { id ->
        homeCollectionKey(id)
    }

    val DEFAULT_HOME_CATALOG_ORDER_KEYS: List<String> = buildList {
        addAll(DEFAULT_HOME_COLLECTION_KEYS)
        addAll(DEFAULT_HOME_CATALOG_KEYS)
        add(INSTALLED_APPS_CATALOG_KEY)
    }

    val CINEMETA_VIDEO_CATALOG_KEYS: List<String> = listOf(
        "com.linvo.cinemeta_series_last-videos",
        "com.linvo.cinemeta_series_last_videos",
        "com.linvo.cinemeta_series_calendar-videos",
        "com.linvo.cinemeta_series_calendar_videos",
        "org.stremio.cinemeta_series_last-videos",
        "org.stremio.cinemeta_series_last_videos",
        "org.stremio.cinemeta_series_calendar-videos",
        "org.stremio.cinemeta_series_calendar_videos",
        "https://v3-cinemeta.strem.io_series_last-videos_Last videos",
        "https://v3-cinemeta.strem.io/_series_last-videos_Last videos",
        "https://v3-cinemeta.strem.io_series_calendar-videos_Calendar videos",
        "https://v3-cinemeta.strem.io/_series_calendar-videos_Calendar videos",
        "com.linvo.cinemeta_movie_year",
        "com.linvo.cinemeta_series_year",
        "org.stremio.cinemeta_movie_year",
        "org.stremio.cinemeta_series_year",
        "https://v3-cinemeta.strem.io_movie_year_New",
        "https://v3-cinemeta.strem.io/_movie_year_New",
        "https://v3-cinemeta.strem.io_series_year_New",
        "https://v3-cinemeta.strem.io/_series_year_New"
    )

    val DEFAULT_DISABLED_STOCK_CATALOG_KEYS: List<String> = listOf(
        "com.linvo.cinemeta_movie_top",
        "com.linvo.cinemeta_series_top",
        "com.linvo.cinemeta_movie_year",
        "com.linvo.cinemeta_series_year",
        "com.linvo.cinemeta_movie_imdbRating",
        "com.linvo.cinemeta_series_imdbRating",
        "org.stremio.cinemeta_movie_top",
        "org.stremio.cinemeta_series_top",
        "org.stremio.cinemeta_movie_year",
        "org.stremio.cinemeta_series_year",
        "org.stremio.cinemeta_movie_imdbRating",
        "org.stremio.cinemeta_series_imdbRating"
    ) + CINEMETA_VIDEO_CATALOG_KEYS + TRAKT_BOXOFFICE_SERIES_CATALOG_KEYS

    fun isMyListsDefaultUrl(url: String): Boolean {
        val clean = url.trim().trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
        return clean.equals(MY_LISTS_DEFAULT_BASE_URL, ignoreCase = true)
    }

    fun isMyListsAddonUrl(url: String): Boolean {
        val clean = url.trim().lowercase()
        return clean.startsWith(MY_LISTS_BASE_ORIGIN.lowercase())
    }

    fun isMyListsAddon(addon: Addon): Boolean {
        return addon.id.equals(MY_LISTS_ADDON_ID, ignoreCase = true) ||
            addon.id.equals(MY_LISTS_COLLECTIONS_ADDON_ID, ignoreCase = true) ||
            isMyListsAddonUrl(addon.baseUrl)
    }

    fun isMyListsAddonId(addonId: String): Boolean {
        return addonId.equals(MY_LISTS_ADDON_ID, ignoreCase = true) ||
            addonId.equals(MY_LISTS_COLLECTIONS_ADDON_ID, ignoreCase = true) ||
            isMyListsAddonUrl(addonId)
    }

    fun isMyListsDefaultCatalogKey(key: String): Boolean {
        return key in DEFAULT_HOME_CATALOG_KEYS ||
            key.startsWith("${MY_LISTS_ADDON_ID}_") ||
            key.startsWith("${MY_LISTS_COLLECTIONS_ADDON_ID}_")
    }

    fun isStockCinemetaAddon(addonId: String, baseUrl: String = ""): Boolean {
        return addonId.equals(CINEMETA_ADDON_ID, ignoreCase = true) ||
            addonId.equals("org.stremio.cinemeta", ignoreCase = true) ||
            addonId.equals("community.cinemeta", ignoreCase = true) ||
            addonId.contains("cinemeta", ignoreCase = true) ||
            (baseUrl.isNotBlank() && baseUrl.contains("cinemeta", ignoreCase = true))
    }

    fun buildDefaultHomeCatalogOrderKeys(collections: List<Collection>): List<String> {
        val collectionKeys = if (collections.isNotEmpty()) {
            collections.map { homeCollectionKey(it.id) }
        } else {
            DEFAULT_HOME_COLLECTION_KEYS
        }
        return buildList {
            addAll(collectionKeys)
            addAll(DEFAULT_HOME_CATALOG_KEYS)
            add(INSTALLED_APPS_CATALOG_KEY)
        }.distinct()
    }

    fun buildDefaultMyListsAddon(
        baseUrl: String = MY_LISTS_DEFAULT_BASE_URL,
        enabled: Boolean = true
    ): Addon {
        val cleanBaseUrl = baseUrl.trim().trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
        return Addon(
            id = MY_LISTS_ADDON_ID,
            name = MY_LISTS_ADDON_NAME,
            displayName = MY_LISTS_ADDON_NAME,
            version = MY_LISTS_ADDON_VERSION,
            description = "Curated lists, combined charts, premade collections, and live channels powered by My Lists.",
            logo = "$MY_LISTS_BASE_ORIGIN/icon.png",
            background = null,
            baseUrl = cleanBaseUrl,
            catalogs = DEFAULT_CATALOG_SPECS + SEARCH_CATALOG_SPECS,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            rawTypes = listOf("movie", "series"),
            resources = listOf(
                AddonResource(
                    name = "catalog",
                    types = listOf("movie", "series"),
                    idPrefixes = listOf("tt", "tmdb:")
                ),
                AddonResource(
                    name = "meta",
                    types = listOf("movie", "series", "channel", "tv"),
                    idPrefixes = listOf("tt", "tmdb:", "mylists:")
                ),
                AddonResource(
                    name = "stream",
                    types = listOf("movie", "series", "channel", "tv"),
                    idPrefixes = listOf("tt", "tmdb:", "mylists:")
                )
            ),
            idPrefixes = listOf("tt", "tmdb:"),
            behaviorHints = AddonBehaviorHints(
                configurable = true,
                configurationRequired = false
            ),
            enabled = enabled
        )
    }
}
