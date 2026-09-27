package com.devbangs.onedevs.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private fun session(expiresAt: Long) = Session("a", "r", expiresAt, "u")

    @Test
    fun `a fresh token is not due for renewal`() {
        assertFalse(session(expiresAt = 10_000_000).needsRefresh(now = 1_000_000))
    }

    @Test
    fun `a token inside the skew window is due for renewal`() {
        // Half a minute left, and the window is a minute.
        assertTrue(session(expiresAt = 1_030_000).needsRefresh(now = 1_000_000))
    }

    @Test
    fun `the skew boundary itself counts as due`() {
        assertTrue(session(expiresAt = 1_000_000 + RefreshSkewMs).needsRefresh(now = 1_000_000))
        assertFalse(
            session(expiresAt = 1_000_000 + RefreshSkewMs + 1).needsRefresh(now = 1_000_000),
        )
    }

    @Test
    fun `an expired token is due for renewal`() {
        assertTrue(session(expiresAt = 500_000).needsRefresh(now = 1_000_000))
    }

    @Test
    fun `expiry is measured from now, not from the server's clock`() {
        val s = TokenResponse("a", "r", expiresIn = 3600, user = TokenUser("u"))
            .toSession(now = 1_000_000)
        assertEquals(4_600_000L, s!!.expiresAt)
    }

    @Test
    fun `a nonsense lifetime cannot push expiry into the past`() {
        val s = TokenResponse("a", "r", expiresIn = -99, user = TokenUser("u"))
            .toSession(now = 1_000_000)
        assertEquals(1_000_000L, s!!.expiresAt)
        assertTrue(s!!.needsRefresh(now = 1_000_000))
    }

    @Test
    fun `a token response with no user and no fallback is not a session`() {
        assertNull(TokenResponse("a", "r", 3600, user = null).toSession(now = 0))
    }

    @Test
    fun `a refresh response carries no user, so the known id is kept`() {
        val s = TokenResponse("a2", "r2", 3600, user = null)
            .toSession(now = 0, fallbackUserId = "u")
        assertEquals("u", s?.userId)
        assertEquals("a2", s?.accessToken)
    }
}
