package com.nuvio.tv.youtube

import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets the Home screen resolve a YouTube video without opening the YouTube page first. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface YouTubePlaybackEntryPoint {
    fun youTubeExtractor(): InAppYouTubeExtractor
}
