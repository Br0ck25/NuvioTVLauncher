package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private fun parse(text: String): M3uPlaylist =
        M3uParser.parse(text.trimIndent().reader().buffered())

    private fun parseSkippingVod(text: String): M3uPlaylist =
        M3uParser.parse(text.trimIndent().reader().buffered()) { LiveTvRepository.isVodEntry(it) }

    @Test
    fun `reads the attributes providers use`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn.us" tvg-name="ESPN" tvg-logo="http://logo/espn.png" group-title="US | Sports",ESPN HD
            http://provider/live/1.ts
            """
        )
        val entry = playlist.entries.single()
        assertEquals("ESPN HD", entry.name)
        assertEquals("espn.us", entry.tvgId)
        assertEquals("ESPN", entry.tvgName)
        assertEquals("http://logo/espn.png", entry.logo)
        assertEquals("US | Sports", entry.group)
        assertEquals("http://provider/live/1.ts", entry.url)
    }

    @Test
    fun `quoted values can contain commas, spaces and equals signs`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="News, Weather & More" catchup="default" catchup-source="http://provider/replay?token=a=b" group-title="US | News",Channel One
            http://provider/live/1.ts
            """
        )
        val entry = playlist.entries.single()
        assertEquals("Channel One", entry.name)
        assertEquals("News, Weather & More", entry.tvgName)
        assertEquals("http://provider/replay?token=a=b", entry.catchup?.source)
    }

    @Test
    fun `unquoted values are read`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id=espn.us tvg-chno=12 group-title=Sports,ESPN
            http://provider/live/1.ts
            """
        )
        val entry = playlist.entries.single()
        assertEquals("espn.us", entry.tvgId)
        assertEquals(12, entry.chno)
        assertEquals("Sports", entry.group)
    }

    @Test
    fun `a quoted attribute wins over an unquoted one either way round`() {
        val quotedLast = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id=plain tvg-id="quoted",Channel
            http://provider/live/1.ts
            """
        ).entries.single()
        val quotedFirst = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="quoted" tvg-id=plain,Channel
            http://provider/live/1.ts
            """
        ).entries.single()
        assertEquals("quoted", quotedLast.tvgId)
        assertEquals("quoted", quotedFirst.tvgId)
    }

    @Test
    fun `the display name starts at the first comma outside quotes`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="A, B",Real Name, With Comma
            http://provider/live/1.ts
            """
        )
        assertEquals("Real Name, With Comma", playlist.entries.single().name)
    }

    @Test
    fun `a byte order mark and CRLF line endings are handled`() {
        val playlist = parse(
            "\uFEFF#EXTM3U\r\n" +
                "#EXTINF:-1 tvg-id=\"ch.1\",Channel\r\n" +
                "http://provider/live/1.ts\r\n"
        )
        val entry = playlist.entries.single()
        assertEquals("ch.1", entry.tvgId)
        assertEquals("Channel", entry.name)
        assertEquals("http://provider/live/1.ts", entry.url)
    }

    @Test
    fun `playlist-wide catch-up defaults apply unless the channel sets its own`() {
        val playlist = parse(
            """
            #EXTM3U catchup="default" catchup-days="3" catchup-source="http://provider/replay/{utc}"
            #EXTINF:-1,From Header
            http://provider/live/1.ts
            #EXTINF:-1 catchup-days="7" catchup-source="http://provider/other/{utc}",Own Values
            http://provider/live/2.ts
            """
        )
        val first = playlist.entries[0].catchup!!
        val second = playlist.entries[1].catchup!!
        assertEquals("default", first.type)
        assertEquals(3, first.days)
        assertEquals("http://provider/replay/{utc}", first.source)
        assertEquals(7, second.days)
        assertEquals("http://provider/other/{utc}", second.source)
    }

    @Test
    fun `VLC and Kodi headers are kept`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=VLC Agent
            #EXTVLCOPT:http-referrer=http://referrer/
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi+Agent&Origin=http%3A%2F%2Forigin%2F
            #EXTINF:-1,Channel
            http://provider/live/1.ts
            """
        )
        val headers = playlist.entries.single().headers
        assertEquals("Kodi Agent", headers["User-Agent"])
        assertEquals("http://referrer/", headers["Referer"])
        assertEquals("http://origin/", headers["Origin"])
    }

    @Test
    fun `headers after the link on a pipe belong to that channel`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            http://provider/live/1.ts|User-Agent=Pipe Agent&Referer=http://pipe/
            """
        )
        val entry = playlist.entries.single()
        assertEquals("http://provider/live/1.ts", entry.url)
        assertEquals("Pipe Agent", entry.headers["User-Agent"])
        assertEquals("http://pipe/", entry.headers["Referer"])
    }

    @Test
    fun `channel numbers and shifts are read`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-chno="27" tvg-shift="+1.5",Channel
            http://provider/live/1.ts
            """
        )
        val entry = playlist.entries.single()
        assertEquals(27, entry.chno)
        assertEquals(90, entry.tvgShiftMinutes)
    }

    @Test
    fun `VOD entries can be skipped before they are built`() {
        val playlist = parseSkippingVod(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=Movie Agent
            #KODIPROP:inputstream.adaptive.stream_headers=Referer=http://movie/
            #EXTINF:-1 group-title="Movies",A Movie
            http://provider/movie/user/pass/1.mp4
            #EXTINF:-1 group-title="News",The News
            http://provider/live/1.ts
            """
        )
        val entry = playlist.entries.single()
        assertEquals("The News", entry.name)
        assertTrue(entry.headers.isEmpty())
    }

    @Test
    fun `an entry with no name gets a fallback name`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch.1",
            http://provider/live/1.ts
            """
        )
        assertEquals("Channel", playlist.entries.single().name)
    }

    @Test
    fun `links without an entry are ignored`() {
        val playlist = parse(
            """
            #EXTM3U
            http://provider/live/orphan.ts
            #EXTINF:-1,Channel
            http://provider/live/1.ts
            """
        )
        assertEquals(1, playlist.entries.size)
        assertEquals("Channel", playlist.entries.single().name)
    }

    @Test
    fun `a missing optional id stays null`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            http://provider/live/1.ts
            """
        )
        assertNull(playlist.entries.single().tvgId)
    }
}
