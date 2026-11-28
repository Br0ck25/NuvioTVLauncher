package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.player.ExternalPlayerResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test

class ServerExternalPlaybackTest {
    private val server = mockk<ServerPlayback>(relaxed = true)
    private val external = ServerExternalPlayback(server)
    private val url = "https://media.example/Videos/1/stream?static=true"

    @Test
    fun `where the external player stopped is reported to the server as a start and a stop`() {
        every { server.isServerSource(url) } returns true
        external.launched(url)
        external.onResult(ExternalPlayerResult(positionMs = 600_000L, durationMs = 5_400_000L, endedByUser = true))
        verifyOrder {
            server.onPlaybackSnapshot(url, 600_000L, true, false, false)
            server.onPlaybackSnapshot(url, 600_000L, false, false, true)
        }
    }

    @Test
    fun `a player that only says it finished reports the end of the item`() {
        every { server.isServerSource(url) } returns true
        external.launched(url)
        external.onResult(ExternalPlayerResult(positionMs = 0L, durationMs = 5_400_000L, endedByUser = false))
        verify { server.onPlaybackSnapshot(url, 5_400_000L, false, false, true) }
    }

    @Test
    fun `no result from the player reports nothing`() {
        every { server.isServerSource(url) } returns true
        external.launched(url)
        external.onResult(null)
        verify(exactly = 0) { server.onPlaybackSnapshot(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a link that is not a server item reports nothing, and a result is only used once`() {
        every { server.isServerSource("https://addon.example/x.mkv") } returns false
        external.launched("https://addon.example/x.mkv")
        external.onResult(ExternalPlayerResult(positionMs = 1_000L, durationMs = 2_000L))
        verify(exactly = 0) { server.onPlaybackSnapshot(any(), any(), any(), any(), any()) }

        every { server.isServerSource(url) } returns true
        external.launched(url)
        external.onResult(ExternalPlayerResult(positionMs = 1_000L, durationMs = 2_000L))
        external.onResult(ExternalPlayerResult(positionMs = 1_500L, durationMs = 2_000L))
        verify(exactly = 2) { server.onPlaybackSnapshot(url, 1_000L, any(), false, any()) }
        verify(exactly = 0) { server.onPlaybackSnapshot(url, 1_500L, any(), any(), any()) }
    }
}
