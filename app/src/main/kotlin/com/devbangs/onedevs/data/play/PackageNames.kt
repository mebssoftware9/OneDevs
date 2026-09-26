package com.devbangs.onedevs.data.play

/**
 * Whether a string is shaped like an Android package name.
 *
 * Shape only. Nothing here says the package exists, belongs to whoever typed
 * it, or is on Play -- it catches the typo that would otherwise be filed as a
 * listing and send testers nowhere.
 *
 * Two segments minimum: single-word package names are legal on paper and
 * absent in practice, and accepting one means accepting "morpho" as a listing.
 */
fun isPackageName(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.length > 255) return false
    val segments = trimmed.split('.')
    if (segments.size < 2) return false
    return segments.all { segment ->
        segment.isNotEmpty() &&
            segment.first().isLetter() &&
            segment.all { it.isLetterOrDigit() || it == '_' }
    }
}
