package com.devbangs.onedevs

import com.devbangs.onedevs.data.backend.HttpResult
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.backend.classify
import com.devbangs.onedevs.data.claims.ClaimBook
import com.devbangs.onedevs.data.claims.ClaimRecord
import com.devbangs.onedevs.data.claims.ClaimStatus
import com.devbangs.onedevs.data.claims.Outcome
import com.devbangs.onedevs.data.claims.outcome
import com.devbangs.onedevs.data.tests.FinishResult
import com.devbangs.onedevs.data.tests.TestStage
import com.devbangs.onedevs.data.tests.TestStatus
import com.devbangs.onedevs.data.tests.decide
import com.devbangs.onedevs.data.tests.decode
import com.devbangs.onedevs.data.tests.epochMillis
import com.devbangs.onedevs.data.tests.reasonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path a reward takes from a finished test to a balance.
 *
 * Every rule here once failed silently: a refusal retried forever, a rejection
 * read as "no signal", a settled claim deleted so the screen forgot it was
 * ever paid. These pin the rules so they cannot quietly come back.
 */
class RewardPathTest {

    // ---- Sorting replies --------------------------------------------------

    @Test
    fun `no signal and server trouble are worth asking again`() {
        assertTrue(classify(HttpResult(0, "timeout")) is Reply.Unreachable)
        listOf(408, 425, 429, 500, 502, 503, 504).forEach {
            assertTrue("$it should be retried", classify(HttpResult(it, "")) is Reply.Unreachable)
        }
    }

    @Test
    fun `a refusal is an answer, not a network fault`() {
        listOf(400, 403, 404, 409, 422).forEach {
            assertTrue("$it should not be retried", classify(HttpResult(it, "no")) is Reply.Rejected)
        }
        assertEquals(Reply.SignedOut, classify(HttpResult(401, "")))
        assertEquals(Reply.Answer("{}"), classify(HttpResult(200, "{}")))
    }

    @Test
    fun `a reply that cannot be read is rejected, not retried`() {
        val garbled = decode(Reply.Answer("<html>gateway</html>"), FinishResult.serializer())
        assertTrue(garbled is Reply.Rejected)
        val fine = decode(Reply.Answer("""{"paid":true,"coins":25}"""), FinishResult.serializer())
        assertEquals(25, (fine as Reply.Answer).value.coins)
    }

    // ---- What a reply means for the claim ---------------------------------

    @Test
    fun `paid and repeat-paid both settle as paid`() {
        assertEquals(Outcome.Paid(25), outcome(Reply.Answer(FinishResult(paid = true, coins = 25))))
        assertEquals(Outcome.Paid(25), outcome(Reply.Answer(FinishResult(paid = true, coins = 25, repeat = true))))
        // The older claim_test says claimed rather than paid.
        assertEquals(Outcome.Paid(25), outcome(Reply.Answer(FinishResult(claimed = true, coins = 25))))
    }

    @Test
    fun `a server refusal keeps its reason`() {
        assertEquals(Outcome.Refused("device_used"), outcome(Reply.Answer(FinishResult(reason = "device_used"))))
        assertEquals(Outcome.Refused("refused"), outcome(Reply.Answer(FinishResult())))
    }

    @Test
    fun `only silence waits, and a rejection is never retried`() {
        assertEquals(Outcome.Later, outcome(Reply.Unreachable("timeout")))
        assertEquals(Outcome.SignedOut, outcome(Reply.SignedOut))
        assertEquals(Outcome.Refused("rejected"), outcome(Reply.Rejected(400, "bad")))
    }

    // ---- The record book ---------------------------------------------------

    private fun claim(id: String, account: String = "a", listing: String = "l") = ClaimRecord(
        id = id, account = account, listingId = listing, title = "App", seconds = 40,
        device = "device-1", coins = 25, at = 0L, session = "s-$id",
    )

    @Test
    fun `settling keeps the record so the screen can say what happened`() {
        val book = ClaimBook.settle(listOf(claim("1")), "1", paid = false, coins = null, reason = "unfunded", now = 5L)
        assertEquals(1, book.size)
        assertEquals(ClaimStatus.Refused, book.single().status)
        assertEquals("unfunded", book.single().reason)
        assertTrue(ClaimBook.pending(book).isEmpty())
    }

    @Test
    fun `a paid claim can never be overwritten by a new question`() {
        val paid = ClaimBook.settle(listOf(claim("1")), "1", paid = true, coins = 25, reason = null, now = 5L)
        val after = ClaimBook.add(paid, claim("2"))
        assertEquals(listOf("1"), after.map { it.id })
        assertEquals(ClaimStatus.Paid, after.single().status)
    }

    @Test
    fun `a refused claim is replaced by a fresh attempt`() {
        val refused = ClaimBook.settle(listOf(claim("1")), "1", paid = false, coins = null, reason = "too_short", now = 5L)
        val after = ClaimBook.add(refused, claim("2"))
        assertEquals(listOf("2"), after.map { it.id })
        assertEquals(ClaimStatus.Pending, after.single().status)
    }

    @Test
    fun `claims of different accounts on one phone are kept apart`() {
        val book = ClaimBook.add(listOf(claim("1", account = "a")), claim("2", account = "b"))
        assertEquals(2, book.size)
        assertEquals("1", ClaimBook.find(book, "a", "l")?.id)
        assertEquals("2", ClaimBook.find(book, "b", "l")?.id)
        assertNull(ClaimBook.find(book, null, "l"))
    }

    @Test
    fun `pending claims are never pruned, settled ones eventually are`() {
        val now = ClaimBook.KEEP_SETTLED_MS * 2
        val old = ClaimBook.settle(listOf(claim("1")), "1", paid = true, coins = 25, reason = null, now = 1L)
        assertTrue(ClaimBook.prune(old, now).isEmpty())
        assertEquals(1, ClaimBook.prune(listOf(claim("2")), now).size)
    }

    @Test
    fun `an unanswered try is counted, not treated as an answer`() {
        val book = ClaimBook.attempted(listOf(claim("1")), "1", now = 9L)
        assertEquals(1, book.single().attempts)
        assertEquals(ClaimStatus.Pending, book.single().status)
    }

    // ---- What the test screen shows ---------------------------------------

    @Test
    fun `work that is done but unconfirmed outranks everything`() {
        val view = decide(claim("1"), TestStatus(state = "unfunded", reward = 25), loading = false)
        assertEquals(TestStage.Confirming, view.stage)
    }

    @Test
    fun `a payment known to either side shows as paid`() {
        val paid = ClaimBook.settle(listOf(claim("1")), "1", paid = true, coins = 25, reason = null, now = 1L).single()
        assertEquals(TestStage.Paid, decide(paid, null, loading = true).stage)
        assertEquals(TestStage.Paid, decide(null, TestStatus(state = "paid", reward = 25), loading = false).stage)
    }

    @Test
    fun `the device rule is shown before the test, not after it`() {
        val view = decide(null, TestStatus(state = "device_used", reward = 25), loading = false)
        assertEquals(TestStage.Blocked, view.stage)
        assertEquals("device_used", view.reason)
    }

    @Test
    fun `an open session resumes where it was`() {
        val view = decide(
            null,
            TestStatus(state = "open", reward = 25, session = "s", startedAt = "2026-09-29T15:22:01.5+00:00"),
            loading = false,
        )
        assertEquals(TestStage.Testing, view.stage)
        assertEquals("s", view.session)
        assertEquals(epochMillis("2026-09-29T15:22:01.5+00:00"), view.startedAt)
    }

    @Test
    fun `a too-short refusal lets the tester carry on`() {
        val refused = ClaimBook.settle(listOf(claim("1")), "1", paid = false, coins = null, reason = "too_short", now = 1L).single()
        val view = decide(refused, TestStatus(state = "open", reward = 25, session = "s-1"), loading = false)
        assertEquals(TestStage.Testing, view.stage)
    }

    @Test
    fun `offline, a local refusal is still explained`() {
        val refused = ClaimBook.settle(listOf(claim("1")), "1", paid = false, coins = null, reason = "unfunded", now = 1L).single()
        val view = decide(refused, null, loading = false)
        assertEquals(TestStage.Blocked, view.stage)
        assertEquals("unfunded", view.reason)
        assertEquals(TestStage.Checking, decide(null, null, loading = true).stage)
        assertEquals(TestStage.Ready, decide(null, null, loading = false).stage)
    }

    @Test
    fun `every reason the server can give has its own sentence`() {
        val generic = reasonText("anything new")
        listOf(
            "already_paid", "device_used", "device_in_use", "unfunded", "too_short",
            "expired", "own_app", "missing", "signed_out",
        ).forEach { assertNotEquals("$it has no sentence of its own", generic, reasonText(it)) }
    }

    @Test
    fun `server timestamps parse`() {
        assertEquals(0L, epochMillis("1970-01-01T00:00:00+00:00"))
        assertNull(epochMillis("not a time"))
        assertNull(epochMillis(null))
    }
}
