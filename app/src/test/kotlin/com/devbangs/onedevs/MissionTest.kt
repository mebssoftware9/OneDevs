package com.devbangs.onedevs

import com.devbangs.onedevs.data.missions.JoinResult
import com.devbangs.onedevs.data.missions.Mission
import com.devbangs.onedevs.data.missions.MissionRules
import com.devbangs.onedevs.data.missions.MissionSeat
import com.devbangs.onedevs.data.missions.MissionStage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mission carries the only numbers in this app that mean something outside
 * it, so the arithmetic is worth pinning rather than trusting.
 */
class MissionRulesTest {

    private fun seats(n: Int) = (1..n).map { MissionSeat(seat = it, listing = "l$it", title = "App $it") }

    @Test
    fun `a full mission leaves every member enough co-testers`() {
        // Sixteen slots exist so that fifteen others test yours. If SLOTS ever
        // drops to thirteen this fails, which is the point: the margin over
        // Google's twelve is the reason for the number.
        val full = Mission("x", "x", seats = seats(MissionRules.SLOTS))
        assertTrue(full.coTesters >= MissionRules.TESTERS_REQUIRED)
        assertTrue(
            "no dropout margin over Google's ${MissionRules.TESTERS_REQUIRED}",
            full.coTesters - MissionRules.TESTERS_REQUIRED >= 2,
        )
    }

    @Test
    fun `the stage is the server's word`() {
        assertEquals(MissionStage.Recruiting, Mission("x", "x", state = "recruiting").stage)
        assertEquals(MissionStage.Running, Mission("x", "x", state = "running").stage)
        assertEquals(MissionStage.Elapsed, Mission("x", "x", state = "elapsed").stage)
        // Anything unknown is treated as still filling, never as finished.
        assertEquals(MissionStage.Recruiting, Mission("x", "x", state = "something new").stage)
    }

    @Test
    fun `open slots and co-testers never go negative`() {
        val empty = Mission("x", "x")
        assertEquals(MissionRules.SLOTS, empty.open)
        assertEquals(0, empty.coTesters)
        assertEquals(0, Mission("x", "x", seats = seats(MissionRules.SLOTS)).open)
    }

    @Test
    fun `a mission reads from what current_mission returns`() {
        val body = """
            {"id":"g1","name":"Mission Ardent","slots":16,"entry_fee":100,"window_days":14,
             "state":"recruiting","day":0,"member":true,
             "seats":[{"seat":1,"listing":"a","title":"Beampad","icon_url":null,"mine":true},
                      {"seat":2,"listing":"b","title":"Morpho","icon_url":"https://x/icon.png","mine":false}]}
        """.trimIndent()
        val mission = Json { ignoreUnknownKeys = true }.decodeFromString(Mission.serializer(), body)
        assertEquals(2, mission.joined)
        assertEquals(14, mission.open)
        assertEquals(100, mission.entryFee)
        assertTrue(mission.member)
        assertTrue(mission.seats.first().mine)
        assertEquals("https://x/icon.png", mission.seats[1].iconUrl)
    }

    @Test
    fun `a refusal is an answer`() {
        val refused = Json.decodeFromString(JoinResult.serializer(), """{"joined":false,"reason":"broke"}""")
        assertFalse(refused.joined)
        assertEquals("broke", refused.reason)
    }

    @Test
    fun `the fee on the page is the fee in the rules`() {
        assertEquals(MissionRules.ENTRY_FEE, Mission("x", "x").entryFee)
        assertEquals(100, MissionRules.ENTRY_FEE)
    }
}
