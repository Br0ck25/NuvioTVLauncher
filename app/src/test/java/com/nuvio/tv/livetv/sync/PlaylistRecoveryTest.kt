package com.nuvio.tv.livetv.sync

import com.nuvio.tv.livetv.model.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRecoveryTest {

    private fun m3u(id: String, url: String) =
        PlaylistSource(id = id, name = id, url = url)

    private fun xtream(id: String, server: String, user: String) =
        PlaylistSource(id = id, name = id, url = "", xtreamServer = server, xtreamUsername = user)

    @Test
    fun `matches the same playlist by URL even when the id changed`() {
        val local = listOf(m3u("local-a", "http://example.com/a.m3u"))
        val cloud = listOf(
            m3u("cloud-a", "http://example.com/a.m3u/"),
            m3u("cloud-b", "http://example.com/b.m3u")
        )

        val missing = PlaylistRecovery.missing(cloud, local)

        assertEquals(listOf("cloud-b"), missing.map { it.id })
    }

    @Test
    fun `matches xtream by server and username`() {
        val local = listOf(xtream("local-a", "http://nas:8080", "alice"))
        val cloud = listOf(
            xtream("cloud-a", "http://NAS:8080/", "Alice"),
            xtream("cloud-b", "http://nas:8080", "bob")
        )

        val missing = PlaylistRecovery.missing(cloud, local)

        assertEquals(listOf("cloud-b"), missing.map { it.id })
    }

    @Test
    fun `duplicate cloud entries are only offered once`() {
        val cloud = listOf(
            m3u("one", "http://example.com/a.m3u"),
            m3u("two", "http://EXAMPLE.com/a.m3u")
        )

        val missing = PlaylistRecovery.missing(cloud, emptyList())

        assertEquals(1, missing.size)
    }

    @Test
    fun `detail only shows the host`() {
        assertEquals(
            "example.com",
            PlaylistRecovery.detail(m3u("a", "http://user:pass@example.com/live.m3u?token=1"))
        )
        assertEquals(
            "nas:8080",
            PlaylistRecovery.detail(xtream("a", "http://nas:8080", "alice"))
        )
    }
}
