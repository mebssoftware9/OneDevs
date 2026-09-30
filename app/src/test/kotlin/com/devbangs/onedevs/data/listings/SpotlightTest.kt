package com.devbangs.onedevs.data.listings

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotlightTest {

    private fun decode(body: String): List<Listing> =
        ListingJson.decodeFromString(ListSerializer(ListingRow.serializer()), body).map { it.toListing() }

    @Test
    fun `a spotlighted board row pays what OneDevs pays, not the listing's reward`() {
        val row = decode(
            """
            [{"id":"s","package_name":"com.s","title":"Shine","category":"Tools","channel":"testing",
              "reward":25,"spotlight":true,"spotlight_reward":50}]
            """,
        ).single()
        assertTrue(row.spotlight)
        assertEquals(50, row.reward)
    }

    @Test
    fun `an ordinary row keeps its own reward`() {
        val row = decode(
            """
            [{"id":"o","package_name":"com.o","title":"Plain","category":"Tools","channel":"testing",
              "reward":25,"spotlight":false,"spotlight_reward":null}]
            """,
        ).single()
        assertFalse(row.spotlight)
        assertEquals(25, row.reward)
    }

    @Test
    fun `a row from before spotlights is not one`() {
        val row = decode(
            """[{"id":"x","package_name":"com.x","title":"Old","category":"Tools","channel":"live","reward":10}]""",
        ).single()
        assertFalse(row.spotlight)
        assertEquals(10, row.reward)
    }
}
