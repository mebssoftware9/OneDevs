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
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.components.DevCoinChip
import com.devbangs.onedevs.ui.navigation.Dashboard
import com.devbangs.onedevs.ui.navigation.MyTests
import com.devbangs.onedevs.ui.navigation.Profile
import com.devbangs.onedevs.ui.navigation.Testers
import com.devbangs.onedevs.ui.navigation.Tools
import com.devbangs.onedevs.ui.navigation.TopLevel
import com.devbangs.onedevs.ui.navigation.Wallet
import com.devbangs.onedevs.ui.screens.DashboardScreen
import com.devbangs.onedevs.ui.screens.MyTestsScreen
import com.devbangs.onedevs.ui.screens.ProfileScreen
import com.devbangs.onedevs.ui.screens.TestersScreen
import com.devbangs.onedevs.ui.screens.ToolsScreen
import com.devbangs.onedevs.ui.screens.WalletScreen

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OneDevsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

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
    ) {
        Scaffold(
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Dashboard,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable<Dashboard> { DashboardScreen() }
                composable<Testers> { TestersScreen() }
                composable<MyTests> { MyTestsScreen() }
                composable<Tools> { ToolsScreen() }
                composable<Profile> { ProfileScreen() }
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
        letterSpacing = (-0.4).sp,
    )
}
