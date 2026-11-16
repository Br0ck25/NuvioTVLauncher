package com.nuvio.tv.livetv.remote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonMapperOverlapTest {
    @Test
    fun `a button changed on a live tv screen is listed with that screen`() {
        val button = RemoteCatalog.buttonForCode(KeyEvent.KEYCODE_PROG_GREEN)!!
        val screen = RemoteCatalog.SCREENS.first()
        val state = RemoteState(map = mapOf(screen.id to mapOf(button.id to mapOf(RemotePress.PRESS.id to "go.sports"))))
        assertEquals(listOf(screen.title), ButtonMapperOverlap.liveTvScreensFor(KeyEvent.KEYCODE_PROG_GREEN, state))
    }

    @Test
    fun `an unchanged button or an unknown key lists nothing`() {
        assertTrue(ButtonMapperOverlap.liveTvScreensFor(KeyEvent.KEYCODE_PROG_RED, RemoteState()).isEmpty())
        assertTrue(ButtonMapperOverlap.liveTvScreensFor(-1, RemoteState()).isEmpty())
    }
}
