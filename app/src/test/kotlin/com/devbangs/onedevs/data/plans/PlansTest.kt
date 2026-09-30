package com.devbangs.onedevs.data.plans

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlansTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a free plan remembers its one app`() {
        val plan = json.decodeFromString(Plan.serializer(), """{"plan":"free","lab_app":"com.x.one"}""")
        assertFalse(plan.pro)
        assertEquals("com.x.one", plan.labApp)
    }

    @Test
    fun `pro from a ghostline run`() {
        val plan = json.decodeFromString(
            Plan.serializer(),
            """{"plan":"pro","pro_until":"2026-10-30T10:00:00+00:00","pro_source":"ghostline","lab_app":null}""",
        )
        assertTrue(plan.pro)
        assertEquals(1_793_354_400_000L, epochOf(plan.proUntil))
    }

    @Test
    fun `android versions by name`() {
        assertEquals("16", androidName(36))
        assertEquals("12L", androidName(32))
        assertEquals("8.0", androidName(26))
        assertEquals("API 21", androidName(21))
    }

    @Test
    fun `a dashboard decodes, and phones group by android version`() {
        val runs = json.decodeFromString(
            ListSerializer(GhostRun.serializer()),
            """
            [{"id":"r","state":"extended","started_at":"2026-09-01T00:00:00+00:00",
              "ends_at":"2026-09-22T00:00:00+00:00","server_now":"2026-09-10T00:00:00+00:00",
              "listing":"l","title":"Focus","package_name":"com.focus","needed":12,"testers":9,
              "active_today":4,"missions":3,"boosts":2,"next_boost_at":null,
              "installs":[{"at":"2026-09-02T00:00:00+00:00","model":"Pixel 8","sdk":35},
                          {"at":"2026-09-03T00:00:00+00:00","model":"Galaxy S24","sdk":34},
                          {"at":"2026-09-04T00:00:00+00:00","model":"Pixel 9","sdk":35},
                          {"at":"2026-09-05T00:00:00+00:00","model":null,"sdk":null}],
              "daily":[{"day":"2026-09-02","testers":1}]}]
            """,
        )
        val run = runs.single()
        assertTrue(run.live)
        assertEquals(listOf(35 to 2, 34 to 1), run.byAndroid)
        assertNull(epochOf(run.nextBoostAt))
    }

    @Test
    fun `plan names map to tiers`() {
        fun tier(name: String) = json.decodeFromString(Plan.serializer(), """{"plan":"$name"}""").tier
        assertEquals(Tier.Community, tier("free"))
        assertEquals(Tier.Premium, tier("premium"))
        assertEquals(Tier.Pro, tier("pro"))
        assertEquals(Tier.Community, tier("something_new"))
    }

    @Test
    fun `a premium lab keeps five`() {
        val plan = json.decodeFromString(
            Plan.serializer(),
            """{"plan":"premium","lab_app":"a.a","lab_apps":["a.a","a.b"],"lab_limit":5}""",
        )
        assertEquals(Tier.Premium, plan.tier)
        assertTrue(plan.labAllows("a.b"))
        assertTrue(plan.labAllows("a.c"))
        val full = plan.copy(labApps = listOf("a.a", "a.b", "a.c", "a.d", "a.e"))
        assertTrue(full.labAllows("a.c"))
        assertFalse(full.labAllows("a.f"))
    }

    @Test
    fun `no limit takes any app, and a reply from before plans gets the smallest lab`() {
        val pro = json.decodeFromString(Plan.serializer(), """{"plan":"pro","lab_apps":["a.a"],"lab_limit":null}""")
        assertTrue(pro.labAllows("a.z"))
        val old = json.decodeFromString(Plan.serializer(), """{"plan":"free","lab_app":"a.a"}""")
        assertEquals(1, old.labLimit)
        assertEquals(listOf("a.a"), old.labKept)
        assertTrue(old.labAllows("a.a"))
        assertFalse(old.labAllows("a.b"))
    }

    @Test
    fun `a bad timestamp is unknown, not zero`() {
        assertNull(epochOf("yesterday"))
    }

    @Test
    fun `a cycle decodes with its spotlight and reports`() {
        val run = json.decodeFromString(
            ListSerializer(GhostRun.serializer()),
            """
            [{"id":"c","kind":"cycle","plan":"premium","state":"running",
              "started_at":"2026-09-01T00:00:00+00:00","ends_at":"2026-09-17T00:00:00+00:00",
              "server_now":"2026-09-09T02:00:00+00:00","listing":"l","title":"Focus",
              "package_name":"com.focus","needed":16,"testers":11,"spotlight":true,
              "spotlight_until":"2026-09-10T00:00:00+00:00","next_spotlight_at":"2026-09-13T00:00:00+00:00",
              "insights":[
                {"day":8,"at":"2026-09-09T00:00:00+00:00","testers":11,"new":4,"avg_seconds":312,
                 "one_day":3,"models":[{"model":"Pixel 8","n":5}],"android":[{"sdk":35,"n":6}]},
                {"day":4,"at":"2026-09-05T00:00:00+00:00","testers":7,"new":7,"avg_seconds":200,
                 "one_day":5,"models":[],"android":[]}]}]
            """,
        ).single()
        assertTrue(run.isCycle)
        assertTrue(run.spotlight)
        assertEquals(16, run.needed)
        assertEquals(listOf(8, 4), run.insights.map { it.day })
        assertEquals(4, run.insights.first().newTesters)
        assertEquals("Pixel 8", run.insights.first().models.single().model)
    }

    @Test
    fun `a ghostline run is not a cycle and has no reports`() {
        val run = json.decodeFromString(GhostRun.serializer(), """{"id":"r"}""")
        assertFalse(run.isCycle)
        assertTrue(run.insights.isEmpty())
    }

    @Test
    fun `a month with its cycles used has none left`() {
        val used = json.decodeFromString(
            CycleAllowance.serializer(),
            """{"plan":"premium","apps":1,"used":1,"needed":16,"next_at":"2026-10-30T10:00:00+00:00"}""",
        )
        assertEquals(0, used.left)
        val free = json.decodeFromString(
            CycleAllowance.serializer(),
            """{"plan":"free","apps":0,"used":0,"needed":null,"next_at":null}""",
        )
        assertEquals(0, free.left)
        assertNull(free.needed)
    }
}
