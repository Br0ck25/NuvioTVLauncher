package com.nuvio.tv.livetv.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSourceCacheIdentityTest {
    private fun xtream(server: String, username: String = "viewer", password: String = "secret") =
        PlaylistSource("id", "Provider", "", xtreamServer = server, xtreamUsername = username, xtreamPassword = password)

    @Test
    fun `same normalized Xtream account shares its cache`() {
        assertTrue(xtream("https://TV.example:443/", "viewer", "secret")
            .sameXtreamAccount(xtream("https://tv.example:443", "viewer", "secret")))
    }

    @Test
    fun `different Xtream credentials do not share a cache`() {
        val source = xtream("https://tv.example:443", "viewer", "secret")
        assertFalse(source.sameXtreamAccount(xtream("https://tv.example:443", "other", "secret")))
        assertFalse(source.sameXtreamAccount(xtream("https://tv.example:443", "viewer", "changed")))
        assertFalse(source.sameXtreamAccount(PlaylistSource("m3u", "M3U", "https://tv.example/list.m3u")))
    }
}
