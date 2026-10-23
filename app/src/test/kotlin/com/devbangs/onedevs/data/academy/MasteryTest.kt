package com.devbangs.onedevs.data.academy

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mastery rules decide what a learner is told they can do, and the server
 * applies the same ones before it issues anything. Each test here is one
 * sentence of the rule, on a competency built by hand.
 */
class MasteryTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val t0 = 1_800_000_000_000L

    private fun variants(n: Int = 2) = List(n) { Variant("p$it", choices = listOf("a", "b", "c"), answer = 0, why = "w") }

    private fun lines() = List(2) { Variant("p$it", code = listOf("x", "y", "z"), answer = 1, why = "w") }

    private fun competency(id: String = "c", gate: Gate? = null, requires: List<String> = emptyList()) = Competency(
        id = id, title = id, outcome = "o", minutes = 10, free = true, requires = requires,
        model = listOf("m", "m"), example = Example(listOf("code")), gate = gate,
        questions = listOf(
            Question("$id-1", QuestionKind.Concept, listOf("h", "h"), variants()),
            Question("$id-2", QuestionKind.Predict, listOf("h", "h"), variants()),
            Question("$id-3", QuestionKind.Debug, listOf("h", "h"), lines()),
            Question("$id-4", QuestionKind.Transfer, listOf("h", "h"), variants()),
        ),
    )

    private fun ev(item: String, at: Long, correct: Boolean = true, hinted: Boolean = false, c: String = "c", variant: Int = 0) =
        Evidence(competency = c, item = item, kind = "concept", variant = variant, correct = correct, hinted = hinted, version = 1, at = at)

    private fun allRight(c: String = "c", at: Long = t0) = (1..4).map { ev("$c-$it", at + it, c = c) }

    @Test
    fun `nothing answered is not started`() {
        val p = Mastery.progress(competency(), emptyList(), t0)
        assertEquals(Standing.NotStarted, p.standing)
        assertFalse(p.answeredAny)
    }

    @Test
    fun `every question right on its own verifies it, with a review owed in a day`() {
        val p = Mastery.progress(competency(), allRight(), t0 + minute)
        assertEquals(Standing.Verified, p.standing)
        assertEquals(t0 + 4, p.verifiedAt)
        assertEquals(t0 + 4 + day, p.reviewAt)
        assertEquals(2, p.secureFormative)
        assertEquals(2, p.secureSummative)
    }

    @Test
    fun `right straight after wrong does not count until ten minutes have passed`() {
        val evidence = allRight().dropLast(1) + ev("c-4", t0, correct = false) + ev("c-4", t0 + 3 * minute)
        val p = Mastery.progress(competency(), evidence, t0 + 4 * minute)
        val q4 = p.questions.single { it.id == "c-4" }
        assertEquals(QuestionState.TooSoon, q4.state)
        assertEquals(t0 + 10 * minute, q4.countsFrom)
        assertEquals(Standing.Learning, p.standing)

        val later = evidence + ev("c-4", t0 + 15 * minute, variant = 1)
        assertEquals(Standing.Verified, Mastery.progress(competency(), later, t0 + 16 * minute).standing)
    }

    @Test
    fun `a hint helps, but does not count`() {
        val evidence = allRight().dropLast(1) + ev("c-4", t0 + 9, hinted = true)
        val p = Mastery.progress(competency(), evidence, t0 + minute)
        assertEquals(QuestionState.Hinted, p.questions.single { it.id == "c-4" }.state)
        assertEquals(Standing.Learning, p.standing)
    }

    @Test
    fun `a gate has to pass on top of the questions`() {
        val gated = competency(gate = Gate(ArtifactGate.TargetSdk, "t"))
        assertEquals(Standing.Learning, Mastery.progress(gated, allRight(), t0 + minute).standing)
        val failed = Evidence(competency = "c", item = Evidence.GATE, kind = Evidence.ARTIFACT, correct = false, version = 1, at = t0 + 10)
        assertEquals(false, Mastery.progress(gated, allRight() + failed, t0 + minute).gatePassed)
        val passed = failed.copy(id = "g2", correct = true, at = t0 + 11)
        val p = Mastery.progress(gated, allRight() + failed + passed, t0 + minute)
        assertEquals(true, p.gatePassed)
        assertEquals(Standing.Verified, p.standing)
    }

    @Test
    fun `a review comes due, and answering it on time moves the next one further out`() {
        val c = competency()
        val due = Mastery.progress(c, allRight(), t0 + 2 * day)
        assertEquals(Standing.ReviewDue, due.standing)

        // Answered early: the schedule does not move.
        val early = allRight() + ev("c-1", t0 + day / 2)
        assertEquals(0, Mastery.progress(c, early, t0 + day / 2).step)

        // Answered when due: one step on, three days away.
        val onTime = allRight() + ev("c-3", t0 + 2 * day)
        val p = Mastery.progress(c, onTime, t0 + 2 * day + minute)
        assertEquals(Standing.Verified, p.standing)
        assertEquals(1, p.step)
        assertEquals(t0 + 2 * day + 3 * day, p.reviewAt)
    }

    @Test
    fun `a review missed sends it back to learning`() {
        val evidence = allRight() + ev("c-3", t0 + 2 * day, correct = false)
        val p = Mastery.progress(competency(), evidence, t0 + 2 * day + minute)
        assertEquals(Standing.Learning, p.standing)
        assertNull(p.reviewAt)
        assertEquals(QuestionState.Missed, p.questions.single { it.id == "c-3" }.state)
    }

    @Test
    fun `the bars round up`() {
        assertEquals(2, Mastery.needed(2, Mastery.FORMATIVE_BAR))
        assertEquals(4, Mastery.needed(5, Mastery.FORMATIVE_BAR))
        assertEquals(6, Mastery.needed(7, Mastery.SUMMATIVE_BAR))
        assertEquals(0, Mastery.needed(0, Mastery.SUMMATIVE_BAR))
    }

    @Test
    fun `practice asks what is not secure first, in a different variant from last time`() {
        val c = competency()
        val evidence = listOf(ev("c-1", t0, variant = 0), ev("c-2", t0 + 1, correct = false, variant = 1))
        val session = Mastery.practice(c, evidence, t0 + minute, Random(1))
        assertEquals(listOf("c-2", "c-3", "c-4", "c-1"), session.map { it.question.id })
        assertEquals(0, session.first().variantIndex)
        assertEquals(1, session.last().variantIndex)
    }

    @Test
    fun `a retry is another variant and is marked as one`() {
        val c = competency()
        val asked = Mastery.practice(c, emptyList(), t0, Random(2)).first()
        val again = Mastery.retry(asked, Random(3))
        assertNotEquals(asked.variantIndex, again.variantIndex)
        assertTrue(again.retry)
    }

    @Test
    fun `choices are shuffled, and the right one is still right`() {
        val q = Question("q", QuestionKind.Concept, emptyList(), listOf(Variant("p", choices = listOf("a", "b", "c", "d"), answer = 2, why = "w")))
        repeat(20) { seed ->
            val asked = Asked("c", q, 0, (0..3).shuffled(Random(seed)))
            val shownAt = asked.order.indexOf(2)
            assertTrue(asked.isRight(shownAt))
            assertEquals(3, (0..3).count { !asked.isRight(it) })
        }
        val line = Asked("c", Question("d", QuestionKind.Debug, emptyList(), lines()), 0, emptyList())
        assertTrue(line.isRight(1))
        assertFalse(line.isRight(0))
    }

    @Test
    fun `a review mixes the competencies that are due, and stays short`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val pathway = Pathway("p", 1, "t", "s", CapstoneSpec("t", "o", 12, 14), ids.map { competency(it) })
        val evidence = ids.flatMap { allRight(it) }
        val review = Mastery.review(pathway, evidence, t0 + 2 * day, Random(4)) { true }
        assertEquals(Mastery.REVIEW_LIMIT, review.size)
        assertEquals(listOf("a", "b", "c", "d", "e"), review.take(5).map { it.competency })
        assertTrue("summative first in each", review.take(5).all { it.question.kind.summative })
        assertTrue(Mastery.review(pathway, evidence, t0 + minute, Random(4)) { true }.isEmpty())
    }

    @Test
    fun `next is a review first, then the first lesson whose prerequisites are done`() {
        val a = competency("a")
        val b = competency("b", requires = listOf("a"))
        val c = competency("c")
        val pathway = Pathway("p", 1, "t", "s", CapstoneSpec("t", "o", 12, 14), listOf(a, b, c))
        val none = Mastery.progress(pathway, emptyList(), t0)
        assertEquals("a", Mastery.next(pathway, none) { true }?.id)

        val aDone = Mastery.progress(pathway, allRight("a"), t0 + minute)
        assertEquals("b", Mastery.next(pathway, aDone) { true }?.id)
        assertEquals("c", Mastery.next(pathway, aDone) { it.id != "b" }?.id)

        val aDue = Mastery.progress(pathway, allRight("a"), t0 + 2 * day)
        assertEquals("a", Mastery.next(pathway, aDue) { true }?.id)
        assertEquals(1, Mastery.dueCount(aDue))
    }
}
