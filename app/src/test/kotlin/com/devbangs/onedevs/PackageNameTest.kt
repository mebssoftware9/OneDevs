package com.devbangs.onedevs

import com.devbangs.onedevs.data.play.isPackageName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNameTest {

    @Test
    fun `real package names pass`() {
        assertTrue(isPackageName("com.devbangs.onedevs"))
        assertTrue(isPackageName("cc.devbangs.morpho"))
        assertTrue(isPackageName("a.b"))
        assertTrue(isPackageName("com.example.my_app2"))
    }

    @Test
    fun `surrounding space does not fail a good name`() {
        assertTrue(isPackageName("  com.devbangs.onedevs  "))
    }

    @Test
    fun `a single word is not a package name`() {
        assertFalse(isPackageName("morpho"))
    }

    @Test
    fun `segments must start with a letter`() {
        assertFalse(isPackageName("com.2fast"))
        assertFalse(isPackageName("_com.example"))
    }

    @Test
    fun `empty segments are rejected`() {
        assertFalse(isPackageName("com..example"))
        assertFalse(isPackageName(".com.example"))
        assertFalse(isPackageName("com.example."))
    }

    @Test
    fun `punctuation and spaces inside are rejected`() {
        assertFalse(isPackageName("com.example app"))
        assertFalse(isPackageName("com.example-app"))
        assertFalse(isPackageName("https://play.google.com/store"))
    }

    @Test
    fun `blank is rejected`() {
        assertFalse(isPackageName(""))
        assertFalse(isPackageName("   "))
    }
}
