package com.devbangs.onedevs.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/** The attest function compares this against its own SHA-256 of the same access token. */
class Sha256Test {

    @Test
    fun `the hash matches the server's, as lowercase hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
    }
}
