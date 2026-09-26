package com.devbangs.onedevs

import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.listings.CheckRecord
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.data.listings.ListingJson
import com.devbangs.onedevs.data.listings.Listings
import com.devbangs.onedevs.data.listings.on
import com.devbangs.onedevs.data.listings.withAdded
import com.devbangs.onedevs.data.listings.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {

    private fun listing(
        id: String,
        pkg: String = "com.devbangs.onedevs",
        channel: Channel = Channel.Testing,
    ) = Listing(id = id, packageName = pkg, title = "OneDevs", category = "Tools", channel = channel)

    @Test
    fun `relisting the same app on the same board replaces it`() {
        val store = Listings().withAdded(listing("a")).withAdded(listing("b"))
        assertEquals(1, store.items.size)
        assertEquals("b", store.items.single().id)
    }

    @Test
    fun `package match is case insensitive when replacing`() {
        val store = Listings()
            .withAdded(listing("a", pkg = "com.devbangs.onedevs"))
            .withAdded(listing("b", pkg = "COM.DEVBANGS.ONEDEVS"))
        assertEquals(1, store.items.size)
    }

    @Test
    fun `the same app on both boards is two listings`() {
        val store = Listings()
            .withAdded(listing("a", channel = Channel.Testing))
            .withAdded(listing("b", channel = Channel.Live))
        assertEquals(2, store.items.size)
        assertEquals(1, store.on(Channel.Testing).size)
        assertEquals(1, store.on(Channel.Live).size)
    }

    @Test
    fun `different apps do not replace each other`() {
        val store = Listings()
            .withAdded(listing("a", pkg = "com.devbangs.morpho"))
            .withAdded(listing("b", pkg = "com.devbangs.search"))
        assertEquals(2, store.items.size)
    }

    @Test
    fun `removing by id leaves the rest`() {
        val store = Listings()
            .withAdded(listing("a", pkg = "one.a"))
            .withAdded(listing("b", pkg = "one.b"))
            .without("a")
        assertEquals(listOf("b"), store.items.map { it.id })
    }

    @Test
    fun `removing an id that is not there changes nothing`() {
        val store = Listings().withAdded(listing("a")).without("zzz")
        assertEquals(1, store.items.size)
    }

    @Test
    fun `a check that never ran claims nothing`() {
        val fresh = CheckRecord()
        assertNull(fresh.publicListing)
        assertNull(fresh.linkMatchesPackage)
        assertEquals(false, fresh.ownerVerified)
    }

    @Test
    fun `listings survive a json round trip`() {
        val original = Listings().withAdded(
            listing("a").copy(
                groupLink = "https://groups.google.com/g/onedevs-testers",
                optInLink = "https://play.google.com/apps/testing/com.devbangs.onedevs",
                testNote = "Check the mission timer across a device rotation.",
                reward = 25,
                createdAt = 1_700_000_000_000L,
                check = CheckRecord(publicListing = false, linkMatchesPackage = true, checkedAt = 1L),
            ),
        )
        val text = ListingJson.encodeToString(Listings.serializer(), original)
        assertEquals(original, ListingJson.decodeFromString(Listings.serializer(), text))
    }

    @Test
    fun `a field from a later build does not destroy the file`() {
        val text = """{"items":[{"id":"a","packageName":"p","title":"t","category":"c",
            "channel":"Testing","somethingFromTheFuture":true}]}""".trimIndent()
        val parsed = ListingJson.decodeFromString(Listings.serializer(), text)
        assertEquals("a", parsed.items.single().id)
        assertTrue(parsed.items.single().check.publicListing == null)
    }
}
