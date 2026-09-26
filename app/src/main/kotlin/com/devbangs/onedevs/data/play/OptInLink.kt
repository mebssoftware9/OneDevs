package com.devbangs.onedevs.data.play

import java.net.URI
import java.util.Locale

/**
 * The links a tester needs to join a Groups-based closed test.
 *
 * There are two, and they are not interchangeable. The Google Group is how a
 * tester gets onto the allowed list; the Play opt-in page is what they open
 * once they are on it. Opening the second without the first just refuses them,
 * which is the single most common way a closed test wastes a tester's time.
 */
sealed interface OptInLink {
    /**
     * play.google.com/apps/testing/<package>. The package is in the path, so a
     * link pasted from another app can be caught without asking anyone.
     */
    data class PlayOptIn(val packageName: String, val url: String) : OptInLink

    /** groups.google.com/g/<name>. Nothing in it ties to a package. */
    data class Group(val url: String) : OptInLink

    /** Parsed, but not a link either kind of tester flow can use. */
    data class Unrecognised(val url: String) : OptInLink
}

/**
 * Reads a link without trusting it.
 *
 * Deliberately uses java.net.URI rather than android.net.Uri: this runs in unit
 * tests, where the android stubs return null for everything and a parser that
 * silently sees nothing is worse than no parser.
 */
fun parseOptInLink(raw: String): OptInLink? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        host == "play.google.com" && segments.size >= 3 &&
            segments[0] == "apps" && segments[1] == "testing" ->
            OptInLink.PlayOptIn(segments[2], trimmed)

        host == "groups.google.com" && segments.size >= 2 && segments[0] == "g" ->
            OptInLink.Group(trimmed)

        else -> OptInLink.Unrecognised(trimmed)
    }
}

/**
 * Whether an opt-in link points at the package it was filed under.
 *
 * Only a Play opt-in link can answer this: a Group link carries no package, so
 * it returns null rather than false. Null is "cannot tell", and the difference
 * matters — showing "does not match" for a link that simply never could is how
 * a check loses its meaning.
 */
fun OptInLink.matchesPackage(packageName: String): Boolean? = when (this) {
    is OptInLink.PlayOptIn -> this.packageName.equals(packageName, ignoreCase = true)
    is OptInLink.Group -> null
    is OptInLink.Unrecognised -> null
}
