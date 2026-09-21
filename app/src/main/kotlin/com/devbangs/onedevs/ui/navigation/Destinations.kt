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
@Serializable object Wallet

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
