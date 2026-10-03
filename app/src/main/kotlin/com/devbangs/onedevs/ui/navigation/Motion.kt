package com.devbangs.onedevs.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.devbangs.onedevs.ui.theme.oneDevsColors

// How the app moves between screens.
//
// Nothing fades while it moves. A fade under a slide leaves both screens
// half there at once, and text from one shows through the other, seems to
// crop, and vanishes. Instead:
//   * into a page, the page slides in from the right edge over the one you
//     were on, which drifts a quarter of the way left beneath it; back is the
//     same motion reversed, and follows the finger during predictive back.
//     Every screen is opaque (see [screen]), so the moving one covers the
//     other cleanly;
//   * between tabs there is no direction, so the old tab leaves in a blink
//     and the new one settles in from just under full size -- one screen at
//     a time.

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

private const val TAB_IN_MS = 260
private const val TAB_OUT_MS = 90
private const val PAGE_MS = 420

private fun NavDestination.isTab(): Boolean = TopLevel.entries.any { hasRoute(it.route::class) }

private val AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs: Boolean
    get() = initialState.destination.isTab() && targetState.destination.isTab()

/** How far the screen underneath drifts: a quarter, enough for depth. */
private fun under(width: Int) = width / 4

private val tabIn: EnterTransition =
    fadeIn(tween(TAB_IN_MS, delayMillis = TAB_OUT_MS, easing = Emphasized)) +
        scaleIn(tween(TAB_IN_MS, delayMillis = TAB_OUT_MS, easing = Emphasized), initialScale = 0.97f)

private val tabOut: ExitTransition = fadeOut(tween(TAB_OUT_MS, easing = Accelerate))

val NavEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenTabs) tabIn else slideInHorizontally(tween(PAGE_MS, easing = Emphasized)) { it }
}

val NavExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenTabs) tabOut else slideOutHorizontally(tween(PAGE_MS, easing = Emphasized)) { -under(it) }
}

val NavPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenTabs) tabIn else slideInHorizontally(tween(PAGE_MS, easing = Emphasized)) { -under(it) }
}

val NavPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenTabs) tabOut else slideOutHorizontally(tween(PAGE_MS, easing = Emphasized)) { it }
}

/**
 * A destination drawn on the page colour, so that while it slides it covers
 * the screen beneath instead of showing it through.
 */
inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val scope = this
        Box(
            Modifier
                .fillMaxSize()
                .background(oneDevsColors.page),
        ) { scope.content(entry) }
    }
}
