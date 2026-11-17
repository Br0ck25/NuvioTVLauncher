package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvChannelVisibilityTagsTest {
    private fun channel(key: String, group: String) = LiveChannel(
        key = key,
        sourceId = "src",
        sourceName = "Source",
        name = key,
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = group,
        group = group,
        number = 0,
        url = "http://example/$key"
    )

    @Test
    fun `tag filter hides matching groups but keeps favorites`() {
        val channels = listOf(
            channel("fav", "AR | Arabic"),
            channel("other", "AR | Arabic"),
            channel("arena", "Arena")
        )
        val user = LiveUserState(favorites = listOf("fav"), groupTagFilters = setOf("AR"))

        val visible = liveTvChannelVisibility(channels, user, LiveTvSettings()).visible

        assertEquals(setOf("fav", "arena"), visible.map { it.key }.toSet())
    }

    @Test
    fun `no tag filter leaves every channel`() {
        val channels = listOf(channel("one", "AR | Arabic"), channel("two", "Arena"))

        val visible = liveTvChannelVisibility(channels, LiveUserState(), LiveTvSettings()).visible

        assertEquals(setOf("one", "two"), visible.map { it.key }.toSet())
    }
}
