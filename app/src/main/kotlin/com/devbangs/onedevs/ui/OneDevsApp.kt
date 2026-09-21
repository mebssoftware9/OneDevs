package com.devbangs.onedevs.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    val surface = MaterialTheme.colorScheme.surface

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { top ->
                val selected = current?.hierarchy?.any { it.hasRoute(top.route::class) } == true
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(top.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            painter = painterResource(if (selected) top.iconSelected else top.icon),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(top.label)) },
                )
            }
        },
        // One continuous surface top to bottom: the navigation bar defaults to
        // surfaceContainer, which reads as a grey slab under the content.
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
                        DevCoinChip(
                            balance = 0,
                            onClick = { navController.navigate(Wallet) },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = surface),
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
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
