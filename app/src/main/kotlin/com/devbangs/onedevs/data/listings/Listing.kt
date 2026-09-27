package com.devbangs.onedevs.data.listings

import kotlinx.serialization.Serializable

/** Which of the two boards an app was put up on. */
@Serializable
enum class Channel { Testing, Live }

/**
 * What a Play check established about a package, and when.
 *
 * Every field is nullable or false-by-default on purpose: this record says what
 * was found out, not what is true. An absent answer and a negative answer are
 * different, and collapsing them is how a listing ends up claiming more than
 * anyone checked.
 */
@Serializable
data class CheckRecord(
    /**
     * true: a public store listing answered, so the app is live.
     * false: nothing answered — consistent with a closed test, and equally
     * consistent with a package that was never published. Never proof of one.
     * null: the question did not get an answer.
     */
    val publicListing: Boolean? = null,

    /**
     * Whether the opt-in link's own path names this package. Null for a group
     * link, which carries no package and so cannot disagree with one.
     */
    val linkMatchesPackage: Boolean? = null,

    val checkedAt: Long = 0L,

    /**
     * Reserved for the only verification that will ever deserve the word:
     * the developer authorising OneDevs against their own Play account, which
     * proves both that they own the package and that a test track exists.
     * Nothing on a device can set this — it needs a server and OAuth — and it
     * is here now so the record does not have to be migrated to hold it later.
     */
    val ownerVerified: Boolean = false,
)

/**
 * An app a developer has put up, on either board.
 *
 * Testing listings carry two links because Groups-based closed testing needs
 * two, in order: the group puts a tester on the allowed list, the Play page is
 * what they open once they are on it. A listing with only the second sends
 * testers to a page that refuses them.
 */
@Serializable
data class Listing(
    val id: String,
    val packageName: String,
    val title: String,
    val category: String,
    val channel: Channel,
    val groupLink: String? = null,
    val optInLink: String? = null,
    val testNote: String? = null,
    /**
     * Absolute path to the icon this developer chose, downscaled to 512 and
     * copied into app storage. A path rather than the bytes: the record is
     * serialised to JSON on every write, and an image inline would rewrite
     * the whole file each time any listing changed.
     */
    val iconPath: String? = null,
    val reward: Int = 0,
    /**
     * Download size as the developer reported it. Null when they did not say.
     * Self-reported by necessity -- nothing available to a phone or to Play
     * can measure an app that was never uploaded here.
     */
    val sizeBytes: Long? = null,
    val createdAt: Long = 0L,
    val check: CheckRecord = CheckRecord(),
)

/** Everything the device has stored. A wrapper so the file has a growable root. */
@Serializable
data class Listings(val items: List<Listing> = emptyList())

/**
 * Adds a listing, replacing any the same app already has on the same board.
 *
 * A developer relisting an app is correcting it, not creating a second one, and
 * two rows for one package is a bug the user cannot fix from the UI. The same
 * package on both boards is a different matter and stays allowed: the
 * Testing Board rejects live apps at check time, not here.
 */
internal fun Listings.withAdded(listing: Listing): Listings = copy(
    items = items.filterNot {
        it.id == listing.id ||
            (
                it.packageName.equals(listing.packageName, ignoreCase = true) &&
                    it.channel == listing.channel
                )
    } + listing,
)

internal fun Listings.without(id: String): Listings =
    copy(items = items.filterNot { it.id == id })

internal fun Listings.on(channel: Channel): List<Listing> = items.filter { it.channel == channel }
