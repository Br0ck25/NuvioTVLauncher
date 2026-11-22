package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAssDialogueDedupeTest {
    private val header = """
        [Script Info]
        ScriptType: v4.00+

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    @Test
    fun `the same line at the same time is kept once`() {
        val text = "$header\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\n"

        val deduped = PlayerAssDialogueDedupe.dedupeText(text)

        assertEquals(1, deduped?.lines()?.count { it.startsWith("Dialogue:") })
    }

    @Test
    fun `the same line at the same start with a slightly different end is kept once`() {
        val text = "$header\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.20,Default,,0,0,0,,مرحبا\n"

        val deduped = PlayerAssDialogueDedupe.dedupeText(text)

        assertEquals(1, deduped?.lines()?.count { it.startsWith("Dialogue:") })
        assertEquals(true, deduped?.contains("0:00:03.20"))
    }

    @Test
    fun `the same line at different times is kept`() {
        val text = "$header\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\n" +
            "Dialogue: 0,0:00:05.00,0:00:07.00,Default,,0,0,0,,مرحبا\n"

        assertNull(PlayerAssDialogueDedupe.dedupeText(text))
    }

    @Test
    fun `different lines at the same time are kept`() {
        val text = "$header\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبا\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,أهلا\n"

        assertNull(PlayerAssDialogueDedupe.dedupeText(text))
    }

    @Test
    fun `files without dialogue lines are left alone`() {
        assertNull(PlayerAssDialogueDedupe.dedupeText("[Script Info]\nTitle: test\n"))
    }
}
