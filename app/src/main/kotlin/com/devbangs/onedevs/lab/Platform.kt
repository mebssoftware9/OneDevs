package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * Android versions, and what each one changes for an app that targets it.
 *
 * Only changes gated on targetSdk are here. Those are the ones a developer
 * opts into by raising the number, which is why they are the ones worth
 * listing: everything else happens to every app whatever it targets, and a
 * list of it would be the release notes.
 */
internal object Platform {

    /** The newest API level this table knows. Raise it with the table. */
    const val LATEST = 37

    private val names = mapOf(
        21 to "5.0", 22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0",
        27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L",
        33 to "13", 34 to "14", 35 to "15", 36 to "16", 37 to "17",
    )

    /** "14" for 34. Null for a level this table has never heard of. */
    fun version(api: Int): String? = names[api]

    /** Behaviour changes switched on by targeting each level. */
    val changes: Map<Int, List<Int>> = mapOf(
        23 to listOf(R.string.bc_23_permissions),
        24 to listOf(R.string.bc_24_file_uri),
        26 to listOf(R.string.bc_26_background, R.string.bc_26_channels),
        28 to listOf(R.string.bc_28_cleartext, R.string.bc_28_fgs),
        29 to listOf(R.string.bc_29_storage),
        30 to listOf(R.string.bc_30_storage, R.string.bc_30_visibility, R.string.bc_30_location),
        31 to listOf(
            R.string.bc_31_exported, R.string.bc_31_pending_intent,
            R.string.bc_31_fgs_background, R.string.bc_31_exact_alarms,
        ),
        33 to listOf(R.string.bc_33_notifications, R.string.bc_33_media),
        34 to listOf(R.string.bc_34_fgs_types, R.string.bc_34_receivers, R.string.bc_34_intents),
        35 to listOf(R.string.bc_35_edge_to_edge),
        36 to listOf(R.string.bc_36_large_screens, R.string.bc_36_back),
        37 to listOf(R.string.bc_37_large_screens),
    )

    /** Changes between two targets, exclusive of [from], inclusive of [to]. */
    fun changesBetween(from: Int, to: Int): List<Pair<Int, List<Int>>> =
        changes.filterKeys { it in (from + 1)..to }.toSortedMap().map { it.key to it.value }
}
