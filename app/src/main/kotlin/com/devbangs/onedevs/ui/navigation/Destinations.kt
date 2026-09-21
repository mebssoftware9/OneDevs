package com.devbangs.onedevs.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.devbangs.onedevs.R
import kotlinx.serialization.Serializable

@Serializable object Dashboard
@Serializable object Testers
@Serializable object MyTests
@Serializable object Tools
@Serializable object Profile
@Serializable object Wallet

/** Top-level destinations. DevCoins is reached from the balance chip, not a tab. */
enum class TopLevel(
    val route: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
) {
    DASHBOARD(Dashboard, R.string.nav_dashboard, R.drawable.ic_squares_four, R.drawable.ic_squares_four_fill),
    TESTERS(Testers, R.string.nav_testers, R.drawable.ic_users_three, R.drawable.ic_users_three_fill),
    MY_TESTS(MyTests, R.string.nav_my_tests, R.drawable.ic_list_checks, R.drawable.ic_list_checks_fill),
    TOOLS(Tools, R.string.nav_tools, R.drawable.ic_wrench, R.drawable.ic_wrench_fill),
    PROFILE(Profile, R.string.nav_profile, R.drawable.ic_user_circle, R.drawable.ic_user_circle_fill),
}
