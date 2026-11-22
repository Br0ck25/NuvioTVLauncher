package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramDisplayTitleTest {

    @Test
    fun `the guide's sub-title is shown beside the title`() {
        val program = EpgProgram(0L, 1L, "College Football", subTitle = "Texas at LSU")
        assertEquals("College Football : Texas at LSU", programDisplayTitle(program))
    }

    @Test
    fun `a title that already names the match-up is left alone`() {
        val program = EpgProgram(0L, 1L, "College Football: Texas at LSU", subTitle = "Texas at LSU")
        assertEquals("College Football: Texas at LSU", programDisplayTitle(program))
    }

    @Test
    fun `a blank sub-title changes nothing`() {
        assertEquals("The King of Queens", programDisplayTitle(EpgProgram(0L, 1L, "The King of Queens")))
        assertEquals("The King of Queens", programDisplayTitle(EpgProgram(0L, 1L, "The King of Queens", subTitle = "  ")))
    }

    @Test
    fun `LIVE and NEW marks still come off the title`() {
        val program = EpgProgram(0L, 1L, "LIVE: College Football", subTitle = "Texas at LSU")
        assertEquals("College Football : Texas at LSU", programDisplayTitle(program))
    }
}
