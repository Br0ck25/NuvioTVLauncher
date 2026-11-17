package com.nuvio.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song

/**
 * Discover: music that is new to the listener. It shares the curator build with
 * Home, so it never repeats the same shelves.
 */
@Composable
internal fun MusicDiscoverScreen(
    curation: MusicCuration,
    loading: Boolean,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenRelease: (MusicRelease) -> Unit,
    onOpenArtist: (MusicRelatedArtist) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        loading && curation.isEmpty -> MusicLoading(modifier.fillMaxSize())
        curation.isEmpty -> MusicEmptyState(
            title = "Discover is still learning",
            message = "Play and like a few songs, then come back for a weekly mix, new releases and related artists.",
            modifier = modifier
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(key = "discover-header") {
                Column(Modifier.padding(horizontal = 26.dp)) {
                    Text(
                        text = "Discover",
                        color = MusicPalette.text,
                        fontFamily = MusicDisplay,
                        fontSize = 30.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Music that's new to you, picked from what you love",
                        color = MusicPalette.textSecondary,
                        fontSize = 10.5.sp
                    )
                }
            }

            curation.discoverWeekly?.let { weekly ->
                item(key = "discover-weekly") {
                    MusicCuratedSongsRow(
                        title = "Discover Weekly",
                        subtitle = "Your weekly mixtape of fresh music — new every week",
                        songs = weekly.tracks,
                        onPlay = onPlaySongs
                    )
                }
            }

            if (curation.releaseRadar.isNotEmpty()) {
                item(key = "release-radar") {
                    Column(Modifier.fillMaxWidth()) {
                        MusicSectionHeader(
                            title = "Release Radar",
                            subtitle = "The latest albums and singles from artists you play"
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 27.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(
                                items = curation.releaseRadar,
                                key = { index, release -> "${release.browseId}#$index" }
                            ) { _, release ->
                                val year = release.year?.toString()
                                MusicCard(
                                    item = ShelfItem(
                                        title = release.title,
                                        subtitle = listOfNotNull(release.artist, year).joinToString(" · "),
                                        thumbnailUrl = release.thumbnailUrl,
                                        videoId = release.videoId,
                                        browseId = release.browseId,
                                        isVideo = false
                                    ),
                                    onClick = { onOpenRelease(release) }
                                )
                            }
                        }
                    }
                }
            }

            if (curation.fansAlsoLike.isNotEmpty()) {
                item(key = "fans-also-like") {
                    Column(Modifier.fillMaxWidth()) {
                        MusicSectionHeader(
                            title = "Fans also like",
                            subtitle = "Artists close to the ones you play, that you haven't played yet"
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 27.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(
                                items = curation.fansAlsoLike,
                                key = { _, artist -> artist.browseId }
                            ) { _, artist ->
                                MusicCard(
                                    item = ShelfItem(
                                        title = artist.name,
                                        subtitle = "Artist",
                                        thumbnailUrl = artist.thumbnailUrl,
                                        videoId = null,
                                        browseId = artist.browseId,
                                        isVideo = false
                                    ),
                                    onClick = { onOpenArtist(artist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
