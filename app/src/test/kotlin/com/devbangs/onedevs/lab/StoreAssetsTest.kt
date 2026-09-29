package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAssetsTest {

    private fun image(w: Int, h: Int, mime: String = "image/png", alpha: Boolean = false, bytes: Long = 200_000) =
        StoreImage("x", w, h, bytes, mime, alpha)

    private fun fails(checks: List<Check>) = checks.filter { it.status == Status.Fail }.map { (it.title as Msg.Str).id }

    private fun png(colorType: Int) = ByteArray(32).also {
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
        it[25] = colorType.toByte()
    }

    @Test
    fun `formats are read from the bytes`() {
        assertEquals("image/png", StoreAssets.sniff(png(2)))
        assertEquals("image/jpeg", StoreAssets.sniff(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)))
        assertEquals("unknown", StoreAssets.sniff(ByteArray(4)))
    }

    @Test
    fun `png alpha comes from the colour type`() {
        assertTrue(StoreAssets.pngAlpha(png(6)))
        assertTrue(StoreAssets.pngAlpha(png(4)))
        assertFalse(StoreAssets.pngAlpha(png(2)))
    }

    @Test
    fun `the icon must be a 512 png under a megabyte`() {
        assertTrue(fails(StoreAssets.icon(image(512, 512, alpha = true))).isEmpty())
        assertTrue(fails(StoreAssets.icon(image(1024, 1024))).contains(R.string.as_icon_size_bad))
        assertTrue(fails(StoreAssets.icon(image(512, 512, "image/jpeg"))).contains(R.string.as_icon_png))
        assertTrue(fails(StoreAssets.icon(image(512, 512, bytes = 2_000_000))).contains(R.string.as_weight_bad))
    }

    @Test
    fun `the feature graphic must be opaque and exactly 1024 by 500`() {
        assertTrue(fails(StoreAssets.featureGraphic(image(1024, 500, "image/jpeg"))).isEmpty())
        assertTrue(fails(StoreAssets.featureGraphic(image(1024, 500, alpha = true))).contains(R.string.as_alpha_bad))
        assertTrue(fails(StoreAssets.featureGraphic(image(1920, 1080))).contains(R.string.as_feature_size_bad))
    }

    @Test
    fun `screenshots obey Play's size and ratio limits`() {
        assertTrue(fails(StoreAssets.screenshot(image(1080, 1920))).isEmpty())
        assertTrue(fails(StoreAssets.screenshot(image(200, 400))).contains(R.string.as_shot_small))
        assertTrue(fails(StoreAssets.screenshot(image(1080, 4000))).contains(R.string.as_shot_large))
        assertTrue(fails(StoreAssets.screenshot(image(1000, 2100))).contains(R.string.as_shot_ratio))
        assertTrue(fails(StoreAssets.screenshot(image(1080, 1920, "image/webp"))).contains(R.string.as_format_bad))
    }

    @Test
    fun `a set needs two to eight, and four large ones to be featured`() {
        val shot = image(1080, 1920)
        assertTrue(fails(StoreAssets.screenshotSet(listOf(shot))).contains(R.string.as_set_few))
        assertTrue(fails(StoreAssets.screenshotSet(List(9) { shot })).contains(R.string.as_set_many))
        val four = StoreAssets.screenshotSet(List(4) { shot })
        assertTrue(four.any { (it.title as Msg.Str).id == R.string.as_set_featured })
    }

    @Test
    fun `images are sorted into the slot their size fits`() {
        assertEquals(AssetSlot.Icon, StoreAssets.guess(image(512, 512)))
        assertEquals(AssetSlot.FeatureGraphic, StoreAssets.guess(image(1024, 500)))
        assertEquals(AssetSlot.Screenshot, StoreAssets.guess(image(1080, 1920)))
    }
}
