package com.nuvio.tv.livetv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvHiddenViewTagsTest {
    @Test
    fun `group tags hide listings but keep favorites`() {
        val view = HiddenView(
            channels = emptySet(),
            groups = emptySet(),
            kept = setOf("fav"),
            tags = setOf("AR")
        )
        val hidden = HiddenEverywhere(listOf(view))

        assertFalse(hidden.hides("fav", "g1", "AR | Arabic"))
        assertTrue(hidden.hides("other", "g1", "AR | Arabic"))
        assertFalse(hidden.hides("arena", "g2", "Arena"))
    }

    @Test
    fun `empty tags keep every listing`() {
        val hidden = HiddenEverywhere(listOf(HiddenView(channels = emptySet(), groups = emptySet())))

        assertFalse(hidden.hides("any", "g", "AR | Arabic"))
    }
}
