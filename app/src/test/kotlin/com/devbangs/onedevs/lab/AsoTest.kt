package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsoTest {

    private fun ids(checks: List<Check>) = checks.map { (it.title as Msg.Str).id }

    private val good = Listing(
        title = "Pocket Budget: Expense Tracker",
        short = "Track spending, set monthly limits and see where your money goes.",
        full = "Pocket Budget is an expense tracker for people who want to see where money goes.\n\n" +
            "Add an expense in two taps, sort it into categories and set a monthly limit for each.\n\n" +
            "Charts show your spending by week and month. Everything stays on your phone.\n\n" +
            "Export to CSV whenever you want, and back up to your own drive. ".repeat(8),
        keywords = listOf("expense tracker", "budget", "spending"),
    )

    @Test
    fun `keywords split on commas and drop duplicates ignoring case and accents`() {
        assertEquals(listOf("café", "Budget"), Aso.keywords("café, Budget,cafe , ,budget"))
    }

    @Test
    fun `phrases match whole words only`() {
        assertEquals(1, Aso.count("Best budget app", "budget"))
        assertEquals(0, Aso.count("budgeting made easy", "budget"))
        assertEquals(2, Aso.count("Expense tracker. An expense  Tracker!", "expense tracker"))
    }

    @Test
    fun `a title over thirty characters fails`() {
        val checks = Aso.title(Listing(title = "A".repeat(10) + " " + "b".repeat(25)))
        assertTrue(checks.any { it.status == Status.Fail && (it.title as Msg.Str).id == R.string.aso_too_long })
    }

    @Test
    fun `promotional words fail in the title but not in the description`() {
        val title = Aso.title(Listing(title = "Free Budget App"))
        assertTrue(ids(title.filter { it.status == Status.Fail }).contains(R.string.aso_promo))
        val full = Aso.full(Listing(full = "Free to use, with new features every month."))
        assertFalse(ids(full).contains(R.string.aso_promo))
    }

    @Test
    fun `ranking claims are flagged in the description too`() {
        val full = Aso.full(Listing(full = "The best budget app on Android."))
        assertTrue(ids(full).contains(R.string.aso_promo))
    }

    @Test
    fun `emoji fail the title only`() {
        assertTrue(ids(Aso.title(Listing(title = "Budget 💰"))).contains(R.string.aso_emoji))
        assertFalse(ids(Aso.short(Listing(short = "Save money 💰"))).contains(R.string.aso_emoji))
    }

    @Test
    fun `placement reports each field`() {
        val p = Aso.placements(good).first()
        assertTrue(p.inTitle)
        assertFalse(p.inShort)
        assertTrue(p.aboveFold)
        assertTrue(p.inFull >= 1)
    }

    @Test
    fun `coverage counts keywords found anywhere`() {
        assertEquals(100, Aso.coverage(good))
        assertEquals(50, Aso.coverage(good.copy(keywords = listOf("budget", "crypto"))))
    }

    @Test
    fun `stuffing is caught`() {
        val stuffed = Listing(full = "budget ".repeat(40) + "word ".repeat(60), keywords = listOf("budget"))
        assertTrue(ids(Aso.full(stuffed)).contains(R.string.aso_stuffed))
    }

    @Test
    fun `a clean listing scores well and a broken one does not`() {
        val high = Aso.total(Aso.score(good))
        val low = Aso.total(Aso.score(Listing(title = "FREE BEST APP!!!", keywords = listOf("budget"))))
        assertTrue("good listing scored $high", high >= 80)
        assertTrue("broken listing scored $low", low <= 20)
        Aso.score(good).forEach { assertTrue(it.points in 0..it.max) }
    }

    @Test
    fun `the checklist names the console forms`() {
        val checks = Aso.checklist(good)
        assertTrue(ids(checks).contains(R.string.aso_cl_safety))
        assertTrue(checks.none { it.status == Status.Fail })
    }
}
