package com.nuvio.tv.livetv.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyListsSyncTest {

    @Test
    fun `a personal target is read from the installed addon's address`() {
        assertEquals("abc123", MyListsSync.personalTarget("https://mylistsaddon.com/abc123/tv/manifest.json"))
        assertEquals("abc123", MyListsSync.personalTarget("https://mylistsaddon.com/abc123/tv"))
        assertEquals("xyz", MyListsSync.personalTarget("https://mylistsaddon.com/u/xyz/tv/manifest.json"))
        assertEquals("xyz", MyListsSync.personalTarget("https://www.mylistsaddon.com/i/xyz/tv/"))
    }

    @Test
    fun `the shared default target and other addresses are never synced`() {
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/nuvio-default/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/default/tv"))
        assertNull(MyListsSync.personalTarget("https://example.com/abc123/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/abc123/collections/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/abc123/manifest.json"))
        assertNull(MyListsSync.personalTarget("https://mylistsaddon.com/tv/manifest.json"))
        assertNull(MyListsSync.personalTarget("not a url"))
    }

    @Test
    fun `the addon base has no manifest and no trailing slash`() {
        assertEquals("https://mylistsaddon.com/abc/tv", MyListsSync.addonBase("https://mylistsaddon.com/abc/tv/manifest.json"))
        assertEquals("https://mylistsaddon.com/abc/tv", MyListsSync.addonBase(" https://mylistsaddon.com/abc/tv/ "))
    }

    @Test
    fun `the server's channels are read with absolute logos and their order`() {
        val json = """
            {"ok":true,"channels":[
              {"id":"ch_1","name":"Sitcoms","logoUrl":"/api/channel-logo?netId=2&v=7","catalogId":"channel_1","playOrder":2},
              {"id":"ch_2","name":"Movies","posterUrl":"https://img.example/p.png","catalogId":"channel_2","playOrder":1},
              {"id":"","name":"No id","catalogId":"x"},
              {"id":"ch_3","name":"","catalogId":"x"},
              {"id":"ch_4","name":"No catalog"},
              {"id":"ch_1","name":"Duplicate","catalogId":"channel_1"}
            ]}
        """.trimIndent()
        val channels = MyListsSync.parseChannels(json)
        assertEquals(listOf("ch_1", "ch_2"), channels.map { it.id })
        assertEquals("https://mylistsaddon.com/api/channel-logo?netId=2&v=7", channels[0].logoUrl)
        assertEquals("https://img.example/p.png", channels[1].logoUrl)
        assertEquals(listOf(2, 1), channels.map { it.order })
    }

    @Test
    fun `an answer that is not ok or not json gives no channels`() {
        assertTrue(MyListsSync.parseChannels("""{"ok":false,"channels":[{"id":"a","name":"A","catalogId":"c"}]}""").isEmpty())
        assertTrue(MyListsSync.parseChannels("<html>").isEmpty())
        assertTrue(MyListsSync.parseChannels("").isEmpty())
    }

    @Test
    fun `a lineup keeps the server's order and drops a description that only repeats the title`() {
        val json = """
            {"metas":[
              {"id":"tmdb:36768:2:9","type":"series","name":"TVography S2E9","poster":"p1","thumbnail":"t1","description":"TVography S2E9"},
              {"id":"tt0903747:1:2","type":"series","name":"Breaking Bad S1E2","poster":"p2","description":"Walt cooks."},
              {"id":"","name":"skipped"}
            ]}
        """.trimIndent()
        val items = MyListsSync.parseLineup(json)
        assertEquals(listOf("tmdb:36768:2:9", "tt0903747:1:2"), items.map { it.id })
        assertEquals("t1", items[0].poster)
        assertNull(items[0].description)
        assertEquals("Walt cooks.", items[1].description)
    }

    @Test
    fun `an exact episode is told from a film or a whole show`() {
        assertEquals(ExactEpisode("tt0903747", 1, 2), MyListsSync.exactEpisode("tt0903747:1:2"))
        assertEquals(ExactEpisode("tmdb:36768", 2, 9), MyListsSync.exactEpisode("tmdb:36768:2:9"))
        assertNull(MyListsSync.exactEpisode("tt0903747"))
        assertNull(MyListsSync.exactEpisode("tmdb:36768"))
        assertNull(MyListsSync.exactEpisode("tt0903747:1"))
        assertNull(MyListsSync.exactEpisode("something:1:2"))
    }

    private fun synced(id: String, name: String = id, catalog: String = "c_$id", base: String = "https://mylistsaddon.com/a/tv", order: Int = 1) =
        ListChannel("mylists:$id", name, emptyList(), 0L, SyncedSource(base, catalog, null, order))

    private fun own(id: String) = ListChannel(id, id, emptyList(), 7L)

    @Test
    fun `the plan adds, renames, moves and removes only synced channels`() {
        val current = listOf(own("mine"), synced("keep"), synced("rename"), synced("moved"), synced("gone"))
        val incoming = listOf(
            synced("keep"),
            synced("rename", name = "Renamed"),
            synced("moved", catalog = "c_new"),
            synced("fresh"),
        )
        val plan = MyListsSync.plan(current, incoming)
        assertEquals(listOf("mylists:fresh"), plan.add.map { it.id })
        assertEquals(setOf("mylists:rename", "mylists:moved"), plan.update.map { it.id }.toSet())
        assertEquals(listOf("mylists:moved"), plan.sourceChanged)
        assertEquals(listOf("mylists:gone"), plan.remove)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `nothing to do when the server matches what is saved`() {
        val channels = listOf(synced("a"), synced("b", order = 2))
        assertTrue(MyListsSync.plan(listOf(own("mine")) + channels, channels).isEmpty)
    }

    @Test
    fun `nothing listed by the server removes every synced channel but not the viewer's own`() {
        val plan = MyListsSync.plan(listOf(own("mine"), synced("a"), synced("b")), emptyList())
        assertEquals(setOf("mylists:a", "mylists:b"), plan.remove.toSet())
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `channels are built from the server's list for the installed addon`() {
        val infos = listOf(SyncedChannelInfo("ch_1", "Sitcoms", "channel_1", "https://logo", 3))
        val channel = MyListsSync.toChannels(infos, "https://mylistsaddon.com/a/tv").single()
        assertEquals("mylists:ch_1", channel.id)
        assertTrue(channel.isSynced)
        assertEquals(SyncedSource("https://mylistsaddon.com/a/tv", "channel_1", "https://logo", 3), channel.synced)
    }
}
