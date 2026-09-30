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
    fun `a bad timestamp is unknown, not zero`() {
        assertNull(epochOf("yesterday"))
    }
}
