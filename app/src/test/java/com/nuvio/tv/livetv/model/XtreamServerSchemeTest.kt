package com.nuvio.tv.livetv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamServerSchemeTest {
    @Test
    fun `an https login link keeps its secure scheme when reduced to the server`() {
        assertEquals(
            "https://example.test:8443",
            PlaylistSource.cleanXtreamServer("https://example.test:8443/get.php?username=user&password=pass&type=m3u_plus"),
        )
        assertEquals(
            "https://example.test",
            PlaylistSource.cleanXtreamServer("HTTPS://example.test/player_api.php?username=user&password=pass"),
        )
    }

    @Test
    fun `a bare host still defaults to http`() {
        assertEquals("http://example.test:8080", PlaylistSource.cleanXtreamServer("example.test:8080"))
    }
}
