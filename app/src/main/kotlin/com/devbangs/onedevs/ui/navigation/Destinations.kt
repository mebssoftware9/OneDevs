package com.devbangs.onedevs.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.devbangs.onedevs.R
import kotlinx.serialization.Serializable

// The loop: test apps on the Board to earn DevCoins, spend DevCoins to put your
// own app up as a Launch. Missions is what you committed to; Launches is what
// you shipped for others to test.
@Serializable object Board
@Serializable object Missions
@Serializable object Launches
@Serializable object Lab
@Serializable object Badge
@Serializable object Profile
@Serializable object Wallet
@Serializable object Plans
@Serializable object Ghostline
@Serializable object Cycles

/**
 * Filing an app on one of the two boards. The board is an argument rather
 * than two destinations: the form is the same shape either way, and the one
 * thing that differs -- a testing listing needing its two opt-in links -- is a
 * branch inside it, not a second screen to keep in step with the first.
 */
@Serializable data class AddListing(
    val live: Boolean,
    val id: String? = null,
    /** A listing on the other board to start from: its details, a new record. */
    val copyOf: String? = null,
)

/**
 * One filed app. Carries the id rather than the record: a route is a place, and
 * a place that embeds its own contents goes stale the moment they are edited.
 */
@Serializable data class AppDetails(val id: String)

/** One mission's own page, where it is joined. */
@Serializable data class MissionDetails(val id: String)

/** A mission's one shared room. */
@Serializable data class MissionCommand(val id: String)

/** Top-level destinations. DevCoins is reached from the balance chip, not a tab. */
enum class TopLevel(
    val route: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
) {
    BOARD(Board, R.string.nav_board, R.drawable.ic_squares_four, R.drawable.ic_squares_four_fill),
    MISSIONS(Missions, R.string.nav_missions, R.drawable.ic_list_checks, R.drawable.ic_list_checks_fill),
    LAUNCHES(Launches, R.string.nav_launches, R.drawable.ic_rocket_launch, R.drawable.ic_rocket_launch_fill),
    LAB(Lab, R.string.nav_lab, R.drawable.ic_flask, R.drawable.ic_flask_fill),
    BADGE(Badge, R.string.nav_badge, R.drawable.ic_medal, R.drawable.ic_medal_fill),
}
