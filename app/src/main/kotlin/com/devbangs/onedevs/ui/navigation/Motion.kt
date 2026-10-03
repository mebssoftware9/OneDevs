package com.devbangs.onedevs.ui.navigation

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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

// How the app moves between screens, after Material's motion system.
//
// The library's default is a 700ms cross-fade in which both screens are half
// there at once: text from one shows through the other and seems to crop and
// vanish. Instead, a screen is never half-shown for long:
//   * between tabs, fade through -- the old one leaves quickly, the new one
//     rises in a touch smaller than full size, so there is one screen at a
//     time;
//   * into a page and back, shared axis -- a short slide on a decelerating
//     curve, the way in going right and the way back going left.

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

private const val IN_MS = 320
private const val OUT_MS = 110
private const val SLIDE_MS = 360

private fun NavDestination.isTab(): Boolean = TopLevel.entries.any { hasRoute(it.route::class) }

private val AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs: Boolean
    get() = initialState.destination.isTab() && targetState.destination.isTab()

/** The width a page travels: a tenth of the screen, enough to read as direction. */
private fun travel(width: Int) = width / 10

val NavEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenTabs) {
        fadeIn(tween(IN_MS, delayMillis = OUT_MS, easing = Emphasized)) +
            scaleIn(tween(IN_MS, delayMillis = OUT_MS, easing = Emphasized), initialScale = 0.96f)
    } else {
        slideInHorizontally(tween(SLIDE_MS, easing = Emphasized)) { travel(it) } +
            fadeIn(tween(SLIDE_MS / 2, delayMillis = OUT_MS / 2, easing = Emphasized))
    }
}

val NavExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenTabs) {
        fadeOut(tween(OUT_MS, easing = Accelerate))
    } else {
        slideOutHorizontally(tween(SLIDE_MS, easing = Emphasized)) { -travel(it) } +
            fadeOut(tween(OUT_MS, easing = Accelerate))
    }
}

val NavPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (betweenTabs) {
        NavEnter(this)
    } else {
        slideInHorizontally(tween(SLIDE_MS, easing = Emphasized)) { -travel(it) } +
            fadeIn(tween(SLIDE_MS / 2, delayMillis = OUT_MS / 2, easing = Emphasized))
    }
}

val NavPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (betweenTabs) {
        NavExit(this)
    } else {
        slideOutHorizontally(tween(SLIDE_MS, easing = Emphasized)) { travel(it) } +
            fadeOut(tween(OUT_MS, easing = Accelerate))
    }
}
