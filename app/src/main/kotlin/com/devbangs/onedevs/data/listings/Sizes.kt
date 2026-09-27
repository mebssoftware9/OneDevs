package com.devbangs.onedevs.data.listings

import java.util.Locale

/**
 * Download size, typed by the developer in megabytes.
 *
 * Nothing on a device or on Play can measure this for us: there is no size API,
 * the APK never reaches OneDevs, and PackageManager only sees what is already
 * installed. So it is self-reported -- which is defensible for this one field,
 * because a developer knows their own build and under-reporting is caught by
 * the first tester who reaches the Play listing. It is never shown as verified.
 */
private const val BYTES_PER_MB = 1024.0 * 1024.0

/**
 * Reads what someone typed. Accepts a comma as the decimal separator: half the
 * languages this app ships in write 12,5 and a parser that only knows 12.5
 * would silently drop their size on the floor.
 */
fun parseMegabytes(input: String): Long? =
    input.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it > 0 && it < 100_000 }
        ?.let { (it * BYTES_PER_MB).toLong() }

/** The same number back in the field, without a trailing .0 to delete. */
fun megabytesOf(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    return if (mb >= 10 || mb == mb.toInt().toDouble()) {
        mb.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", mb)
    }
}
