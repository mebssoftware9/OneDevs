package com.devbangs.onedevs

import com.devbangs.onedevs.lab.DexCounts
import com.devbangs.onedevs.lab.dexHeaderForTest
import com.devbangs.onedevs.lab.mergeDexForTest
import com.devbangs.onedevs.lab.sliceSizesForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DEX header is fixed-layout, so it can be built in a test and read back.
 * Nothing here needs a device or a real APK -- which is the point of keeping
 * the parsing apart from the file handling.
 */
class DexHeaderTest {

    /** Little-endian uint32 at a known offset, the way the format stores it. */
    private fun header(
        methods: Int = 0, fields: Int = 0, classes: Int = 0, strings: Int = 0, types: Int = 1,
        magic: ByteArray = byteArrayOf(0x64, 0x65, 0x78, 0x0A),
        size: Int = 0x70,
    ): ByteArray {
        val b = ByteArray(size)
        magic.copyInto(b, 0, 0, minOf(magic.size, size))
        fun put(at: Int, v: Int) {
            if (at + 3 >= size) return
            b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte()
            b[at + 2] = (v shr 16).toByte(); b[at + 3] = (v shr 24).toByte()
        }
        put(0x38, strings); put(0x40, types); put(0x50, fields)
        put(0x58, methods); put(0x60, classes)
        return b
    }

    @Test
    fun `reads the five counts from their documented offsets`() {
        val counts = dexHeaderForTest(header(methods = 40000, fields = 12000, classes = 3000, strings = 51000))
        assertEquals(DexCounts(1, 40000, 12000, 3000, 51000), counts)
    }

    @Test
    fun `refuses anything that is not a dex`() {
        assertNull(dexHeaderForTest(header(magic = byteArrayOf(0x50, 0x4B, 0x03, 0x04))))
        assertNull(dexHeaderForTest(ByteArray(0x20)))
        assertNull(dexHeaderForTest(ByteArray(0)))
    }

    @Test
    fun `refuses a count that cannot be real`() {
        // A field read as negative means it was above 2^31: truncated, or not
        // a DEX. Returning a plausible-looking report from it would be worse
        // than returning nothing.
        assertNull(dexHeaderForTest(header(methods = -1)))
    }

    @Test
    fun `multidex adds up, and the single-dex ceiling is per app`() {
        val merged = mergeDexForTest(
            listOf(DexCounts(1, 60000, 1, 1, 1), DexCounts(1, 20000, 1, 1, 1)),
        )
        assertEquals(2, merged.files)
        assertEquals(80000, merged.methods)
        assertTrue(merged.overSingleDexLimit)
        assertTrue(!DexCounts(1, 65_536, 0, 0, 0).overSingleDexLimit)
        assertTrue(DexCounts(1, 65_537, 0, 0, 0).overSingleDexLimit)
    }
}

/** How an APK's entries are grouped into the slices a developer recognises. */
class SizeSliceTest {

    @Test
    fun `entries land in the slice they belong to`() {
        val slices = sliceSizesForTest(
            listOf(
                "classes.dex" to 1000L, "classes2.dex" to 500L,
                "lib/arm64-v8a/libfoo.so" to 4000L,
                "res/drawable/a.png" to 300L, "resources.arsc" to 200L,
                "assets/data.json" to 100L, "META-INF/CERT.RSA" to 50L,
                "AndroidManifest.xml" to 20L, "stamp-cert-sha256" to 10L,
            ),
        )
        val by = slices.associate { it.label to it.bytes }
        assertEquals(1500L, by["DEX"])
        assertEquals(4000L, by["Native libraries"])
        assertEquals(300L, by["Resources"])
        assertEquals(200L, by["Resource table"])
        assertEquals(100L, by["Assets"])
        assertEquals(50L, by["Signing"])
        assertEquals(20L, by["Manifest"])
        assertEquals(10L, by["Other"])
    }

    @Test
    fun `the biggest slice comes first, because that is the one to act on`() {
        val slices = sliceSizesForTest(
            listOf("res/a" to 10L, "lib/x/y.so" to 900L, "classes.dex" to 400L),
        )
        assertEquals(listOf("Native libraries", "DEX", "Resources"), slices.map { it.label })
    }

    @Test
    fun `an empty apk produces no slices rather than zeroes`() {
        assertTrue(sliceSizesForTest(emptyList()).isEmpty())
    }
}
