package com.devbangs.onedevs

import com.devbangs.onedevs.ui.missions.Mission
import com.devbangs.onedevs.ui.missions.MissionRules
import com.devbangs.onedevs.ui.missions.MissionStage
import com.devbangs.onedevs.ui.missions.SampleMissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mission carries the only numbers in this app that mean something outside
 * it, so the arithmetic is worth pinning rather than trusting.
 */
class MissionRulesTest {

    @Test
    fun `a full mission leaves every member enough co-testers`() {
        // Sixteen slots exist so that fifteen others test yours. If SLOTS ever
        // drops to thirteen this fails, which is the point: the margin over
        // Google's twelve is the reason for the number.
        val full = Mission("x", "x", MissionRules.SLOTS, 0, 0, 0, 0, false)
        assertTrue(full.coTesters >= MissionRules.TESTERS_REQUIRED)
        assertTrue(
            "no dropout margin over Google's ${MissionRules.TESTERS_REQUIRED}",
            full.coTesters - MissionRules.TESTERS_REQUIRED >= 2,
        )
    }

    @Test
    fun `gathering until full, running until the window ends, then elapsed`() {
        assertEquals(
            MissionStage.Gathering,
            Mission("x", "x", MissionRules.SLOTS - 1, 0, 0, 0, 0, false).stage,
        )
        assertEquals(
            MissionStage.Running,
            Mission("x", "x", MissionRules.SLOTS, 1, 0, 0, 0, false).stage,
        )
        assertEquals(
            MissionStage.Elapsed,
            Mission("x", "x", MissionRules.SLOTS, MissionRules.WINDOW_DAYS, 0, 0, 0, false).stage,
        )
    }

    @Test
    fun `open slots and co-testers never go negative`() {
        val empty = Mission("x", "x", 0, 0, 0, 0, 0, false)
        assertEquals(MissionRules.SLOTS, empty.open)
        assertEquals(0, empty.coTesters)
    }
}

/** The samples have to obey the rules they illustrate. */
class SampleMissionTest {

    @Test
    fun `nothing is over-subscribed or past the window`() {
        SampleMissions.forEach {
            assertTrue("${it.name} has ${it.joined} in ${MissionRules.SLOTS} slots", it.joined <= MissionRules.SLOTS)
            assertTrue("${it.name} is on day ${it.day}", it.day in 0..MissionRules.WINDOW_DAYS)
        }
    }

    @Test
    fun `a mission only counts days once it is full`() {
        SampleMissions.filter { it.stage == MissionStage.Gathering }
            .forEach { assertEquals("${it.name} counts days while gathering", 0, it.day) }
    }

    @Test
    fun `tasks done never exceed tasks total, and every mission pays`() {
        SampleMissions.forEach {
            assertTrue("${it.name}: ${it.tasksDone} of ${it.tasksTotal}", it.tasksDone <= it.tasksTotal)
            assertTrue("${it.name} pays ${it.payout}", it.payout > 0)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = SampleMissions.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }
}
