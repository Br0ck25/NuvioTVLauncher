package com.nuvio.tv.livetv.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPartsTest {

    @Test
    fun `addon playlist sources sync with playlists and logins`() {
        assertEquals(SyncPart.LOGINS, SyncParts.partOf("addon_channels"))
        assertEquals(SyncPart.LOGINS, SyncParts.partOf("addon_channels_p2"))
    }

    @Test
    fun `playlist and guide keys keep their existing part`() {
        listOf("playlists", "epg_sources", "playlist_accounts", "epg_overrides").forEach { key ->
            assertEquals(SyncPart.LOGINS, SyncParts.partOf(key))
        }
    }
}
