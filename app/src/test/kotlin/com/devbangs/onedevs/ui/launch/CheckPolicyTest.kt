package com.devbangs.onedevs.ui.launch

import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.play.PlayListing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckPolicyTest {

    @Test
    fun `a live listing cannot go on the testing board`() {
        assertTrue(blocksListing(Channel.Testing, PlayListing.Live))
    }

    @Test
    fun `a live listing is exactly what live apps is for`() {
        assertFalse(blocksListing(Channel.Live, PlayListing.Live))
    }

    @Test
    fun `no public listing never blocks, on either board`() {
        // Play returns 404 for a genuine closed test and for a package that was
        // never published, with identical bodies. Blocking on it would reject
        // real closed tests to catch typos it cannot actually identify.
        assertFalse(blocksListing(Channel.Testing, PlayListing.NotPublic))
        assertFalse(blocksListing(Channel.Live, PlayListing.NotPublic))
    }

    @Test
    fun `an unreachable Play never blocks`() {
        // Otherwise the worst connection is punished hardest, and whoever this
        // was aimed at simply turns off their data.
        assertFalse(blocksListing(Channel.Testing, PlayListing.Unknown(0)))
        assertFalse(blocksListing(Channel.Testing, PlayListing.Unknown(500)))
        assertFalse(blocksListing(Channel.Live, PlayListing.Unknown(0)))
    }

    @Test
    fun `nothing is blocked before the check has answered`() {
        assertFalse(blocksListing(Channel.Testing, null))
        assertFalse(blocksListing(Channel.Live, null))
    }
}
