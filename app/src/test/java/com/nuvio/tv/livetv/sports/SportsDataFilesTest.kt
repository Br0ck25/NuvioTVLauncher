package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new NuvioOne sports data files: FotMob extra leagues, their games and wrestling/boxing. */
class SportsDataFilesTest {

    private val extraLeague = League(
        id = "tsdb/197",
        sport = Sport.FOOTBALL,
        name = "Poland I Liga",
        short = "PIL"
    )

    @Test
    fun `the index keeps FotMob leagues, their kind and their teams`() {
        val raw = """
            {"leagues":[
              {"id":"197","name":"Poland I Liga","sport":"FOOTBALL",
               "words":["I Liga","Poland I Liga"],"regions":["US","CA","UK","IE","AU","OTHER"],
               "short":"PIL","kind":"teams","minutes":115,
               "badge":"https://images.fotmob.com/image_resources/logo/leaguelogo/197.png",
               "teams":[{"id":"2186","name":"Cracovia",
                 "badge":"https://images.fotmob.com/image_resources/logo/teamlogo/2186.png"}]},
              {"id":"198","name":"Puchar Polski","sport":"FOOTBALL","words":["Puchar Polski"],
               "regions":["US"],"short":"PP","kind":"event","minutes":120}
            ]}
        """.trimIndent()

        val list = SportsFeeds.parseFootballIndex(raw)

        assertEquals(2, list.size)
        val league = list[0]
        assertEquals("tsdb/197", league.league.id)
        assertEquals("Poland I Liga", league.league.name)
        assertEquals("PIL", league.league.short)
        assertEquals(LeagueKind.TEAMS, league.league.kind)
        assertTrue("Poland I Liga" in league.league.guideWords)
        assertEquals(1, league.teams.size)
        assertEquals("Cracovia", league.teams[0].name)
        assertNotNull(league.teams[0].logo)
        assertEquals(LeagueKind.EVENT, list[1].league.kind)
    }

    @Test
    fun `an extra league game reads string scores and marks the winner`() {
        val raw = """
            {"season":"2026/2027","events":[
              {"id":"5861969","hasTime":true,"start":"2026-10-31T14:30:00Z",
               "home":"Lechia Gdansk","away":"Skierniewice","homeId":"8030","awayId":"2417",
               "state":"post","round":13,"status":"FT","hs":"3","as":"2"}
            ]}
        """.trimIndent()

        val events = SportsFeeds.parseFootballLeague(extraLeague, raw, emptyMap(), now = 1_800_000_000_000L)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(GameState.POST, event.state)
        assertEquals("3", event.home?.score)
        assertEquals("2", event.away?.score)
        assertTrue(event.home?.winner == true)
        assertTrue(event.away?.winner == false)
        assertEquals("Round 13", event.subtitle)
    }

    @Test
    fun `the wrestling file puts TNA in its own league and keeps boxing separate`() {
        val raw = """
            {"leagues":[{"key":"tna","badge":"https://example.com/tna.png"}],
             "events":[
              {"id":"tna-1","name":"TNA iMPACT!","league":"tna",
               "start":"2026-11-20T00:00:00Z","hasTime":true,"venue":"Edmonton EXPO Centre"},
              {"id":"boxing-1","name":"Bryan Flores vs. Jose Nunez","league":"boxing",
               "start":"2026-11-14T01:00:00Z","hasTime":true}
            ]}
        """.trimIndent()

        val byLeague = SportsFeeds.parseWrestling(raw, now = 1_800_000_000_000L)

        assertEquals("TNA iMPACT!", byLeague["guide/tna"]?.single()?.title)
        assertEquals("Bryan Flores vs. Jose Nunez", byLeague["guide/boxing"]?.single()?.title)
        assertTrue("guide/wrestling" !in byLeague)
    }

    @Test
    fun `an extra league table reads the FotMob columns`() {
        val raw = """
            {"season":"2026/2027","table":[
              {"id":"2183","name":"Polonia Warszawa","pos":1,"p":14,"w":7,"d":4,"l":3,
               "gd":8,"pts":25,"form":"WWDDW","note":"Promotion",
               "badge":"https://images.fotmob.com/image_resources/logo/teamlogo/2183.png"}
            ]}
        """.trimIndent()

        val table = Standings.parseTsdb(extraLeague, raw, now = 0L)

        assertNotNull(table)
        assertEquals("FotMob", table!!.source)
        assertEquals("2026/2027", table.season)
        val row = table.groups.single().rows.single()
        assertEquals("Polonia Warszawa", row.name)
        assertEquals(1, row.pos)
        assertEquals("25", row.values["points"])
        assertEquals("+8", row.values["pointDifferential"])
        assertEquals("WWDDW", row.form)
        assertEquals("Promotion", row.zone?.label)
    }
}
