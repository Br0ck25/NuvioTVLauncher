package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SportsExtrasPlayersTest {
    @Test
    fun parsesAllTeamLeadersAndBasketballStandouts() {
        val event = SportsEvent(
            id = "game",
            leagueId = "nba",
            startMs = 0L,
            hasTime = true,
            state = GameState.POST,
            title = "Away at Home",
            subtitle = null,
            status = "Final",
            away = Side(team("1", "Away"), "98", null, false, false),
            home = Side(team("2", "Home"), "101", null, true, true)
        )
        val root = JSONObject(
            """
            {
              "leaders": [
                {"team":{"id":"1"},"leaders":[
                  {"shortDisplayName":"Points","leaders":[{"displayValue":"30","value":30,"athlete":{"id":"a1","displayName":"Away Star","jersey":"7","position":{"abbreviation":"G"},"headshot":{"href":"https://img/a1.png"}}}]},
                  {"shortDisplayName":"Rebounds","leaders":[{"displayValue":"8","value":8,"athlete":{"id":"a2","displayName":"Away Big"}}]},
                  {"shortDisplayName":"Assists","leaders":[{"displayValue":"9","value":9,"athlete":{"id":"a3","displayName":"Away Guard"}}]}
                ]},
                {"team":{"id":"2"},"leaders":[
                  {"shortDisplayName":"Points","leaders":[{"displayValue":"27","value":27,"athlete":{"id":"h1","displayName":"Home Star"}}]},
                  {"shortDisplayName":"Rebounds","leaders":[{"displayValue":"11","value":11,"athlete":{"id":"h2","displayName":"Home Big"}}]},
                  {"shortDisplayName":"Assists","leaders":[{"displayValue":"6","value":6,"athlete":{"id":"h3","displayName":"Home Guard"}}]}
                ]}
              ],
              "boxscore":{"players":[
                {"team":{"id":"1"},"statistics":[{"labels":["MIN","PTS","REB","AST"],"athletes":[
                  {"athlete":{"id":"a4","displayName":"Away Role Player"},"stats":["20","18","4","2"]}
                ]}]},
                {"team":{"id":"2"},"statistics":[{"labels":["MIN","PTS","REB","AST"],"athletes":[
                  {"athlete":{"id":"h4","displayName":"Home Role Player"},"stats":["24","22","6","1"]}
                ]}]}
              ]}
            }
            """.trimIndent()
        )

        val extras = SportsExtras.parse(root, event, Sport.BASKETBALL)

        assertEquals(6, extras.performers.size)
        assertEquals(listOf("POINTS", "REBOUNDS", "ASSISTS"), extras.performers.map { it.category }.distinct())
        val first = extras.performers.first()
        assertEquals("a1", first.athleteId)
        assertEquals("https://img/a1.png", first.photo)
        assertEquals("G", first.position)
        assertEquals(30.0, first.value!!, 0.0)
        assertEquals(listOf("Home Role Player", "Away Role Player"), extras.standouts.map { it.name })
        assertEquals("22 PTS · 6 REB · 1 AST", extras.standouts.first().line)
        assertNotNull(extras.standouts.first().athleteId)
    }

    private fun team(id: String, name: String) = TeamInfo(
        leagueId = "nba", id = id, name = name, fullName = name, abbr = name.take(3),
        location = null, color = null, altColor = null, logo = null
    )
}
