package com.devbangs.onedevs.data.wallet

import com.devbangs.onedevs.data.backend.BackendJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletTest {

    private fun wallet(json: String) = BackendJson.decodeFromString(Wallet.serializer(), json)

    @Test
    fun `available is the balance less what is held`() {
        val w = wallet("""{"balance":75,"held":25,"entries":[]}""")
        assertEquals(25, w.held)
        assertEquals(50, w.available)
    }

    @Test
    fun `a reply without held is unknown, not zero`() {
        val w = wallet("""{"balance":75,"entries":[]}""")
        assertNull(w.held)
        assertNull(w.available)
    }

    @Test
    fun `available never goes below zero`() {
        assertEquals(0, wallet("""{"balance":10,"held":25}""").available)
    }
}
