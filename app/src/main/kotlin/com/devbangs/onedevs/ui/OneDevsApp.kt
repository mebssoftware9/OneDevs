package com.devbangs.onedevs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devbangs.onedevs.ui.components.DevCoinChip
import com.devbangs.onedevs.ui.components.ProfileAction
import com.devbangs.onedevs.ui.navigation.Badge
import com.devbangs.onedevs.ui.navigation.Board
import com.devbangs.onedevs.ui.navigation.Lab
import com.devbangs.onedevs.ui.navigation.Launches
import com.devbangs.onedevs.ui.navigation.Missions
import com.devbangs.onedevs.ui.navigation.TopLevel
import com.devbangs.onedevs.ui.navigation.Wallet
import com.devbangs.onedevs.ui.screens.BadgeScreen
import com.devbangs.onedevs.ui.screens.BoardScreen
import com.devbangs.onedevs.ui.screens.LabScreen
import com.devbangs.onedevs.ui.screens.LaunchesScreen
import com.devbangs.onedevs.ui.screens.MissionsScreen
import com.devbangs.onedevs.ui.screens.WalletScreen

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OneDevsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surface

    // Which tab the current destination belongs to, or null on the wallet.
    // Nothing is faked when it is null: lighting a tab the user is not on is a
    // worse lie than lighting none, and the chip shows where they are instead.
    val activeTop = TopLevel.entries.firstOrNull { top ->
        current?.hierarchy?.any { it.hasRoute(top.route::class) } == true
    }

    // One definition of what entering a top-level destination means. The tabs
    // and the profile action both go through it, so the action switches to
    // Badge rather than stacking a second copy of it on the back stack.
    val openTopLevel: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    // No active-item pill. Material fills the selected item with
    // secondaryContainer, and across five destinations that slab is the largest
    // block of colour on screen while belonging to no part of the brand.
    // Selection is carried instead by the icon switching to its fill weight and
    // both icon and label taking the brand blue, which reads without a container.
    //
    // All three item sets are configured, not just the bar: the suite swaps to a
    // rail and then a drawer as the window widens, and leaving those at their
    // defaults would put the indicator back on a tablet.
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = scheme.primary,
            selectedTextColor = scheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = scheme.primary,
            selectedTextColor = scheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Color.Transparent,
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = scheme.primary,
            selectedTextColor = scheme.primary,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { top ->
                val selected = top == activeTop
                item(
                    selected = selected,
                    onClick = { openTopLevel(top.route) },
                    icon = {
                        Icon(
                            painter = painterResource(if (selected) top.iconSelected else top.icon),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(top.label)) },
                    colors = itemColors,
                )
            }
        },
        // One sheet from the status bar to below the gesture bar. NavigationBar
        // paints its container outside its own inset padding, so holding every
        // surface at colorScheme.surface — which the palette pins to pure white
        // and true black — carries the same colour under the system bars with no
        // seam. The generated scheme also sets surfaceTint to surface, which is
        // what stops tonal elevation tinting the bar a shade off the page.
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = surface,
            navigationRailContainerColor = surface,
            navigationDrawerContainerColor = surface,
        ),
        containerColor = surface,
    ) {
        Scaffold(
            containerColor = surface,
            topBar = {
                TopAppBar(
                    title = { Wordmark() },
                    actions = {
                        // Both actions are 38dp inside a 48dp touch target, so
                        // each carries 5dp of its own on either side. 6dp here
                        // puts 11dp between them and 11dp from the edge.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            DevCoinChip(
                                balance = 0,
                                onClick = {
                                    navController.navigate(Wallet) { launchSingleTop = true }
                                },
                            )
                            // Badge already owns reputation, devices and account
                            // settings. The profile action opens that rather than
                            // introducing a second destination for one screen.
                            ProfileAction(onClick = { openTopLevel(Badge) })
                        }
                    },
                    // scrolledContainerColor defaults to surfaceContainer, which
                    // drops a grey band over the page as soon as content scrolls.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = surface,
                        scrolledContainerColor = surface,
                    ),
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Board,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable<Board> { BoardScreen() }
                composable<Missions> { MissionsScreen() }
                composable<Launches> { LaunchesScreen() }
                composable<Lab> { LabScreen() }
                composable<Badge> { BadgeScreen() }
                composable<Wallet> { WalletScreen() }
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("One") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Devs") }
        },
        // headlineSmall bold, not titleLarge semibold. The mark has to outweigh
        // the two actions across from it, and at 22sp it did not.
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
}
