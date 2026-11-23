package com.nuvio.tv.livetv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvGroupTagsTest {
    private fun channel(key: String, group: String, groupId: String = group) = LiveChannel(
        key = key,
        sourceId = "src",
        sourceName = "Source",
        name = key,
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = groupId,
        group = group,
        number = 0,
        url = "http://example/$key"
    )

    @Test
    fun `matches whole tags, not substrings`() {
        assertFalse(LiveTvGroupTags.matches("Arena Sports", setOf("AR")))
        assertTrue(LiveTvGroupTags.matches("AR | Arabic", setOf("AR")))
        assertTrue(LiveTvGroupTags.matches("Sky Sports AR", setOf("ar")))
        assertTrue(LiveTvGroupTags.matches("Arabic", setOf("ARABIC")))
        assertTrue(LiveTvGroupTags.matches("United States | Sports", setOf("United States")))
        assertFalse(LiveTvGroupTags.matches("United States | Sports", setOf("US")))
    }

    @Test
    fun `scan counts channels and groups and skips quality tags`() {
        val tags = LiveTvGroupTags.scan(
            listOf(
                channel("1", "US | Entertainment", "g1"),
                channel("2", "US | Sports", "g2"),
                channel("3", "AR | Arabic", "g3"),
                channel("4", "Arena", "g4"),
                channel("5", "HD | US", "g5")
            )
        ).associateBy { it.label.lowercase() }

        assertEquals(3, tags["us"]?.channelCount)
        assertEquals(3, tags["us"]?.groupCount)
        assertEquals(1, tags["ar"]?.channelCount)
        assertEquals(1, tags["arabic"]?.channelCount)
        assertEquals(1, tags["arena"]?.channelCount)
        assertNull(tags["hd"])
    }

    @Test
    fun `country-only scan lists countries instead of every group word`() {
        val tags = LiveTvGroupTags.scan(
            listOf(
                channel("1", "US | Entertainment", "g1"),
                channel("2", "US | Sports", "g2"),
                channel("3", "AR | Arabic", "g3"),
                channel("4", "Arena", "g4"),
                channel("5", "HD | US", "g5"),
                channel("6", "United States | Sports", "g6"),
                channel("7", "Sky Sports AR", "g7"),
                channel("8", "UK: News", "g8")
            ),
            countriesOnly = true
        ).associateBy { it.label.lowercase() }

        assertEquals(3, tags["us"]?.channelCount)
        assertEquals(3, tags["us"]?.groupCount)
        assertEquals(2, tags["ar"]?.channelCount)
        assertEquals(1, tags["united states"]?.channelCount)
        assertEquals(1, tags["uk"]?.channelCount)
        assertNull(tags["entertainment"])
        assertNull(tags["sports"])
        assertNull(tags["arena"])
        assertNull(tags["arabic"])
        assertNull(tags["news"])
    }

    @Test
    fun `country tokens keep whole country names and skip ordinary words`() {
        assertEquals(setOf("United States"), LiveTvGroupTags.countryTokens("United States | Sports"))
        assertEquals(setOf("AR"), LiveTvGroupTags.countryTokens("Sky Sports AR"))
        assertEquals(setOf("US"), LiveTvGroupTags.countryTokens("Sky Sports US"))
        assertEquals(emptySet<String>(), LiveTvGroupTags.countryTokens("Arena"))
        assertEquals(emptySet<String>(), LiveTvGroupTags.countryTokens("Entertainment in HD"))
        assertEquals(setOf("Türkiye"), LiveTvGroupTags.countryTokens("Türkiye | Spor"))
        assertEquals(setOf("Turkiye"), LiveTvGroupTags.countryTokens("Turkiye | Spor"))
    }

    @Test
    fun `favorites are exempt from the tag filter`() {
        val user = LiveUserState(favorites = listOf("fav"), groupTagFilters = setOf("AR"))

        assertFalse(user.hidesByGroupTag(channel("fav", "AR | Arabic")))
        assertTrue(user.hidesByGroupTag(channel("other", "AR | Arabic")))
        assertFalse(user.hidesByGroupTag(channel("arena", "Arena")))
    }

    @Test
    fun `custom group channels are exempt from the tag filter`() {
        val custom = CustomGroup(id = "custom", name = "Mine", channelKeys = listOf("kept"))
        val user = LiveUserState(customGroups = listOf(custom), groupTagFilters = setOf("AR"))

        assertFalse(user.hidesByGroupTag(channel("kept", "AR | Arabic"), user.keptChannelKeys))
        assertTrue(user.hidesByGroupTag(channel("other", "AR | Arabic"), user.keptChannelKeys))
    }
}
