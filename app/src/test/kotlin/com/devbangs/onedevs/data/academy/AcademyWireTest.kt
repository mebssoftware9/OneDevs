package com.devbangs.onedevs.data.academy

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes to the server and what comes back, and how the two are folded together. */
class AcademyWireTest {

    private fun ev(id: String, at: Long, sent: Boolean = false, pkg: String? = null) =
        Evidence(id = id, competency = "c", item = "q", kind = "concept", correct = true, version = 1, at = at, packageName = pkg, sent = sent)

    @Test
    fun `answers go out with an ISO time, and a package only for a gate`() {
        val plain = AcademyWire.toWire(ev("a", 1_800_000_000_000L), "ship-to-play")
        assertEquals("2027-01-15T08:00:00Z", plain["at"]!!.jsonPrimitive.content)
        assertEquals("ship-to-play", plain["pathway"]!!.jsonPrimitive.content)
        assertFalse("package" in plain)
        val gate = AcademyWire.toWire(ev("b", 0, pkg = "com.x"), "ship-to-play")
        assertEquals("com.x", gate["package"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Postgres times read back to the same instant`() {
        assertEquals(1_800_000_000_123L, AcademyWire.millis("2027-01-15T08:00:00.123+00:00"))
        assertEquals(1_800_000_000_000L, AcademyWire.millis("2027-01-15T09:00:00+01:00"))
        assertNull(AcademyWire.millis("yesterday"))
    }

    @Test
    fun `the server's copy marks the phone's as sent and fills in what the phone lacks`() {
        val local = listOf(ev("a", 1), ev("b", 3))
        val remote = listOf(ev("a", 1, sent = true), ev("c", 2, sent = true))
        val merged = AcademyWire.merge(local, remote)
        assertEquals(listOf("a", "c", "b"), merged.map { it.id })
        assertTrue(merged.single { it.id == "a" }.sent)
        assertFalse(merged.single { it.id == "b" }.sent)
    }

    @Test
    fun `unsent answers go in batches the server accepts, and sent ones never go back`() {
        val log = (1..250).map { ev("e$it", it.toLong(), sent = it <= 30) }
        val batches = AcademyWire.unsentBatches(log)
        assertEquals(listOf(100, 100, 20), batches.map { it.size })
        val marked = AcademyWire.markSent(log, batches.first().map { it.id }.toSet())
        assertEquals(120, marked.count { !it.sent })
    }

    @Test
    fun `a competency opens on the plan that pays for it`() {
        val c = Competency("c", "t", "o", 10, free = false, model = emptyList(), example = Example(emptyList()), questions = emptyList())
        assertFalse(c.openOn(null))
        assertFalse(c.openOn(com.devbangs.onedevs.data.plans.Plan(plan = "free")))
        assertTrue(c.openOn(com.devbangs.onedevs.data.plans.Plan(plan = "premium")))
        assertTrue(c.openOn(com.devbangs.onedevs.data.plans.Plan(plan = "pro")))
        assertTrue(c.copy(free = true).openOn(null))
    }
}
