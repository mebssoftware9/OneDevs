package com.devbangs.onedevs

import com.devbangs.onedevs.data.play.OptInLink
import com.devbangs.onedevs.data.play.matchesPackage
import com.devbangs.onedevs.data.play.parseOptInLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptInLinkTest {

    @Test
    fun `play opt-in link yields its package`() {
        val link = parseOptInLink("https://play.google.com/apps/testing/com.devbangs.onedevs")
        assertEquals(OptInLink.PlayOptIn("com.devbangs.onedevs",
            "https://play.google.com/apps/testing/com.devbangs.onedevs"), link)
    }

    @Test
    fun `trailing slash and www do not change the package`() {
        val link = parseOptInLink("https://www.play.google.com/apps/testing/com.devbangs.onedevs/")
        assertEquals("com.devbangs.onedevs", (link as OptInLink.PlayOptIn).packageName)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val link = parseOptInLink("  https://play.google.com/apps/testing/com.devbangs.search  ")
        assertEquals("com.devbangs.search", (link as OptInLink.PlayOptIn).packageName)
    }

    @Test
    fun `group link is recognised as a group`() {
        assertTrue(parseOptInLink("https://groups.google.com/g/onedevs-testers") is OptInLink.Group)
    }

    @Test
    fun `anything else is unrecognised`() {
        assertTrue(parseOptInLink("https://example.com/join") is OptInLink.Unrecognised)
    }

    @Test
    fun `blank and malformed input yield null`() {
        assertNull(parseOptInLink(""))
        assertNull(parseOptInLink("   "))
        assertNull(parseOptInLink("not a url at all ::::"))
    }

    @Test
    fun `a link for another app does not match`() {
        val link = parseOptInLink("https://play.google.com/apps/testing/com.someone.else")!!
        assertEquals(false, link.matchesPackage("com.devbangs.onedevs"))
    }

    @Test
    fun `case differences still match`() {
        val link = parseOptInLink("https://play.google.com/apps/testing/COM.DEVBANGS.ONEDEVS")!!
        assertEquals(true, link.matchesPackage("com.devbangs.onedevs"))
    }

    @Test
    fun `a group link cannot answer the package question`() {
        val link = parseOptInLink("https://groups.google.com/g/onedevs-testers")!!
        assertNull(link.matchesPackage("com.devbangs.onedevs"))
    }
}
