package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodHomeIdsTest {

    @Test
    fun `meta id round trips`() {
        val uid = "movie:playlist-1:12345"
        assertEquals(uid, VodHomeIds.uidOf(VodHomeIds.metaId(uid)))
        assertNull(VodHomeIds.uidOf("tt0111161"))
    }

    @Test
    fun `on demand content ids are recognised`() {
        assertTrue(VodHomeIds.isOnDemandContentId("nuviovod:movie:playlist-1:12345"))
        assertTrue(VodHomeIds.isOnDemandContentId("nuviovod:series:playlist-1:678_s1e2"))
        assertFalse(VodHomeIds.isOnDemandContentId("tt0111161"))
        assertFalse(VodHomeIds.isOnDemandContentId("trakt:123"))
        assertFalse(VodHomeIds.isOnDemandContentId(null))
    }

    @Test
    fun `progress ids resolve to the provider item uid`() {
        assertEquals(
            "movie:playlist-1:12345",
            VodHomeIds.uidOfProgress("nuviovod:movie:playlist-1:12345")
        )
        // Series progress may carry the episode key suffix.
        assertEquals(
            "series:playlist-1:678",
            VodHomeIds.uidOfProgress("nuviovod:series:playlist-1:678_s2e10")
        )
        assertNull(VodHomeIds.uidOfProgress("tt0111161"))
        assertNull(VodHomeIds.uidOfProgress("nuviovod:"))
    }
}
