package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/** One image picked for the store listing, as far as a check needs to know it. */
data class StoreImage(
    val name: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    /** "image/png", "image/jpeg", or whatever the picker reported. */
    val mime: String,
    /** Whether the decoded image carries an alpha channel. */
    val alpha: Boolean,
)

/** Which slot of the listing an image is meant for. */
enum class AssetSlot { Icon, FeatureGraphic, Screenshot }

/**
 * Play Console's rules for listing graphics, checked before the upload that
 * would reject them.
 *
 * The numbers are Google's and live here because this is where someone will
 * look when Google changes one.
 */
object StoreAssets {

    const val ICON_SIZE = 512
    const val ICON_MAX_BYTES = 1024L * 1024
    const val FEATURE_WIDTH = 1024
    const val FEATURE_HEIGHT = 500
    const val FEATURE_MAX_BYTES = 15L * 1024 * 1024
    const val SCREENSHOT_MIN = 320
    const val SCREENSHOT_MAX = 3840
    const val SCREENSHOT_MAX_BYTES = 8L * 1024 * 1024
    const val SCREENSHOTS_MIN_COUNT = 2
    const val SCREENSHOTS_MAX_COUNT = 8

    /** Play features apps whose phone screenshots are at least this on the short side. */
    const val SCREENSHOT_FEATURED_MIN = 1080
    const val SCREENSHOTS_FEATURED_COUNT = 4

    private const val PNG = "image/png"
    private const val JPEG = "image/jpeg"

    private fun mb(bytes: Long) = Msg.Raw("%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024)))

    private fun size(i: StoreImage) = Msg.Raw("${i.width} × ${i.height}")

    /** Bytes enough to tell the format and, for PNG, the colour type. */
    const val HEAD_BYTES = 32

    /**
     * The format from the file's first bytes rather than its name or what the
     * picker claims: a screenshot saved as .png by a tool that wrote WebP is
     * still rejected as WebP.
     */
    fun sniff(head: ByteArray): String {
        fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xff) ?: -1
        return when {
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> PNG
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> JPEG
            at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code -> "image/webp"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "image/gif"
            else -> "unknown"
        }
    }

    /**
     * Whether a PNG has an alpha channel, from IHDR's colour type: 4 is grey
     * with alpha, 6 is RGBA. That is what Play means by "32-bit", whether or
     * not any pixel is actually transparent.
     */
    fun pngAlpha(head: ByteArray): Boolean {
        if (sniff(head) != PNG || head.size < 26) return false
        val colorType = head[25].toInt() and 0xff
        return colorType == 4 || colorType == 6
    }

    /** A slot's guess for an image nobody said the purpose of. */
    fun guess(i: StoreImage): AssetSlot = when {
        i.width == ICON_SIZE && i.height == ICON_SIZE -> AssetSlot.Icon
        i.width == FEATURE_WIDTH && i.height == FEATURE_HEIGHT -> AssetSlot.FeatureGraphic
        else -> AssetSlot.Screenshot
    }

    fun icon(i: StoreImage): List<Check> = buildList {
        add(
            if (i.width == ICON_SIZE && i.height == ICON_SIZE) {
                Check(Status.Pass, str(R.string.as_icon_size_ok))
            } else {
                Check(Status.Fail, str(R.string.as_icon_size_bad, size(i)), str(R.string.as_icon_size_bad_d))
            },
        )
        add(
            if (i.mime == PNG) {
                Check(Status.Pass, str(R.string.as_png_ok))
            } else {
                Check(Status.Fail, str(R.string.as_icon_png), str(R.string.as_icon_png_d))
            },
        )
        add(
            if (i.bytes <= ICON_MAX_BYTES) {
                Check(Status.Pass, str(R.string.as_weight_ok, mb(i.bytes)))
            } else {
                Check(Status.Fail, str(R.string.as_weight_bad, mb(i.bytes), mb(ICON_MAX_BYTES)))
            },
        )
        if (i.alpha) add(Check(Status.Info, str(R.string.as_icon_alpha), str(R.string.as_icon_alpha_d)))
    }

    fun featureGraphic(i: StoreImage): List<Check> = buildList {
        add(
            if (i.width == FEATURE_WIDTH && i.height == FEATURE_HEIGHT) {
                Check(Status.Pass, str(R.string.as_feature_size_ok))
            } else {
                Check(Status.Fail, str(R.string.as_feature_size_bad, size(i)), str(R.string.as_feature_size_bad_d))
            },
        )
        addAll(opaque(i))
        add(
            if (i.bytes <= FEATURE_MAX_BYTES) {
                Check(Status.Pass, str(R.string.as_weight_ok, mb(i.bytes)))
            } else {
                Check(Status.Fail, str(R.string.as_weight_bad, mb(i.bytes), mb(FEATURE_MAX_BYTES)))
            },
        )
        add(Check(Status.Info, str(R.string.as_feature_safe), str(R.string.as_feature_safe_d)))
    }

    fun screenshot(i: StoreImage): List<Check> = buildList {
        val short = minOf(i.width, i.height)
        val long = maxOf(i.width, i.height)
        add(
            when {
                short < SCREENSHOT_MIN ->
                    Check(Status.Fail, str(R.string.as_shot_small, size(i)), str(R.string.as_shot_limits_d))
                long > SCREENSHOT_MAX ->
                    Check(Status.Fail, str(R.string.as_shot_large, size(i)), str(R.string.as_shot_limits_d))
                long > short * 2 ->
                    Check(Status.Fail, str(R.string.as_shot_ratio, size(i)), str(R.string.as_shot_ratio_d))
                else -> Check(Status.Pass, str(R.string.as_shot_size_ok, size(i)))
            },
        )
        addAll(opaque(i))
        if (i.bytes > SCREENSHOT_MAX_BYTES) {
            add(Check(Status.Fail, str(R.string.as_weight_bad, mb(i.bytes), mb(SCREENSHOT_MAX_BYTES))))
        }
        if (short in SCREENSHOT_MIN until SCREENSHOT_FEATURED_MIN) {
            add(Check(Status.Warn, str(R.string.as_shot_soft), str(R.string.as_shot_soft_d)))
        }
    }

    /** The set as a whole: how many, and whether they agree with each other. */
    fun screenshotSet(shots: List<StoreImage>): List<Check> = buildList {
        add(
            when {
                shots.size < SCREENSHOTS_MIN_COUNT ->
                    Check(Status.Fail, str(R.string.as_set_few, num(shots.size)), str(R.string.as_set_few_d))
                shots.size > SCREENSHOTS_MAX_COUNT ->
                    Check(Status.Fail, str(R.string.as_set_many, num(shots.size)), str(R.string.as_set_many_d))
                else -> Check(Status.Pass, str(R.string.as_set_ok, num(shots.size)))
            },
        )
        val featured = shots.count { minOf(it.width, it.height) >= SCREENSHOT_FEATURED_MIN && valid(it) }
        add(
            if (featured >= SCREENSHOTS_FEATURED_COUNT) {
                Check(Status.Pass, str(R.string.as_set_featured))
            } else {
                Check(Status.Info, str(R.string.as_set_not_featured, num(featured)), str(R.string.as_set_not_featured_d))
            },
        )
        val orientations = shots.map { it.width >= it.height }.distinct()
        if (orientations.size > 1) {
            add(Check(Status.Warn, str(R.string.as_set_mixed), str(R.string.as_set_mixed_d)))
        }
        val shapes = shots.map { it.width to it.height }.distinct()
        if (shapes.size > 1) {
            add(
                Check(
                    Status.Info,
                    str(R.string.as_set_sizes, num(shapes.size)),
                    str(R.string.as_set_sizes_d),
                    shapes.map { Msg.Raw("${it.first} × ${it.second}") },
                ),
            )
        }
    }

    fun check(slot: AssetSlot, i: StoreImage): List<Check> = when (slot) {
        AssetSlot.Icon -> icon(i)
        AssetSlot.FeatureGraphic -> featureGraphic(i)
        AssetSlot.Screenshot -> screenshot(i)
    }

    /** Feature graphics and screenshots: JPEG or 24-bit PNG, no transparency. */
    private fun opaque(i: StoreImage): List<Check> = buildList {
        when {
            i.mime != PNG && i.mime != JPEG ->
                add(Check(Status.Fail, str(R.string.as_format_bad, Msg.Raw(i.mime)), str(R.string.as_format_bad_d)))
            i.alpha ->
                add(Check(Status.Fail, str(R.string.as_alpha_bad), str(R.string.as_alpha_bad_d)))
            else -> add(Check(Status.Pass, str(R.string.as_format_ok)))
        }
    }

    private fun valid(i: StoreImage): Boolean = screenshot(i).none { it.status == Status.Fail }
}
