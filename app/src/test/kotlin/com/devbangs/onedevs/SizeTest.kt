package com.devbangs.onedevs

import com.devbangs.onedevs.data.listings.megabytesOf
import com.devbangs.onedevs.data.listings.parseMegabytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizeTest {

    @Test
    fun `whole megabytes round trip`() {
        val bytes = parseMegabytes("12")!!
        assertEquals(12L * 1024 * 1024, bytes)
        assertEquals("12", megabytesOf(bytes))
    }

    @Test
    fun `a comma decimal is not dropped`() {
        assertEquals(parseMegabytes("4.8"), parseMegabytes("4,8"))
        assertEquals("4.8", megabytesOf(parseMegabytes("4,8")!!))
    }

    @Test
    fun `surrounding space is tolerated`() {
        assertEquals(parseMegabytes("12"), parseMegabytes("  12  "))
    }

    @Test
    fun `nonsense and impossible sizes are rejected`() {
        assertNull(parseMegabytes(""))
        assertNull(parseMegabytes("big"))
        assertNull(parseMegabytes("0"))
        assertNull(parseMegabytes("-5"))
        assertNull(parseMegabytes("999999"))
    }

    @Test
    fun `large sizes lose the pointless decimal`() {
        assertEquals("78", megabytesOf(parseMegabytes("78.0")!!))
    }
}
