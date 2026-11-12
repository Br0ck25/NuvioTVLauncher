package com.nuvio.tv.livetv.lists

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MyListsLineupFetchTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    private val lineup = """{"metas":[
        {"id":"tt0903747:1:2","type":"series","name":"Breaking Bad S1E2","poster":"p"},
        {"id":"tmdb:36768:2:9","type":"series","name":"TVography S2E9","poster":"q"}]}"""

    private fun source() = SyncedSource(server.url("/abc/tv").toString().trimEnd('/'), "channel_1", null, 1)

    @Test
    fun `a lineup is read in the server's order from the series catalog`() = runBlocking {
        server.enqueue(MockResponse().setBody(lineup))
        val (items, _) = MyListsSync.fetchLineup(client, source())!!
        assertEquals(listOf("tt0903747:1:2", "tmdb:36768:2:9"), items.map { it.id })
        val request = server.takeRequest()
        assertEquals("/abc/tv/catalog/series/channel_1.json", request.path)
        assertEquals("https://mylistsaddon.com", request.getHeader("Origin"))
    }

    @Test
    fun `the tv catalog is tried when the series one has nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(lineup))
        val (items, _) = MyListsSync.fetchLineup(client, source())!!
        assertEquals(2, items.size)
        assertEquals("/abc/tv/catalog/series/channel_1.json", server.takeRequest().path)
        assertEquals("/abc/tv/catalog/tv/channel_1.json", server.takeRequest().path)
    }

    @Test
    fun `a lineup the server cannot give is null, and a changed lineup has another fingerprint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(MyListsSync.fetchLineup(client, source()))

        server.enqueue(MockResponse().setBody(lineup))
        val first = MyListsSync.fetchLineup(client, source())!!.second
        server.enqueue(MockResponse().setBody(lineup.replace("S1E2", "S1E3")))
        val second = MyListsSync.fetchLineup(client, source())!!.second
        assertNotNull(first)
        assert(first != second)
    }

    @Test
    fun `synced channels survive being saved and read back, next to the viewer's own`() {
        val saved = HashMap<String, String>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { saved[firstArg()] = secondArg(); editor }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { saved[firstArg()] ?: secondArg() }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val own = ListChannel("1-2", "Mine", listOf(ListRef.Library("watchlist", "Watchlist")), 42L)
        val synced = ListChannel("mylists:ch_1", "Sitcoms", emptyList(), 0L, SyncedSource("https://mylistsaddon.com/a/tv", "channel_1", "https://logo", 3))
        val store = ListChannelStore(context, 1)
        store.saveChannels(listOf(own, synced))

        val back = ListChannelStore(context, 1).channels()
        assertEquals(listOf(own, synced), back)
        assertNull(back[0].synced)
    }
}
