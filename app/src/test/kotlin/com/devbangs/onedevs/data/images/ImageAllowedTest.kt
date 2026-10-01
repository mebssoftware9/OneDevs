package com.devbangs.onedevs.data.images

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAllowedTest {

    private val own = "abcdefghijklmnopqrst.supabase.co"

    @Test
    fun `own storage and Google account pictures are fetched`() {
        assertTrue(imageAllowed("https://$own/storage/v1/object/public/icons/u/l.png", own))
        assertTrue(imageAllowed("https://lh3.googleusercontent.com/a/photo=s96-c", own))
    }

    @Test
    fun `anywhere else is not`() {
        for (url in listOf(
            "http://$own/storage/v1/object/public/icons/u/l.png",
            "https://example.com/huge.png",
            "https://example.com/$own/x.png",
            "https://$own@example.com/x.png",
            "https://$own.example.com/x.png",
            "https://googleusercontent.com.example.com/x.png",
            "file:///data/data/com.devbangs.onedevs/x.png",
            "not a url",
        )) {
            assertFalse(url, imageAllowed(url, own))
        }
    }

    @Test
    fun `no project address means only Google pictures`() {
        assertFalse(imageAllowed("https://$own/x.png", ""))
        assertTrue(imageAllowed("https://lh3.googleusercontent.com/a/x", ""))
    }
}
