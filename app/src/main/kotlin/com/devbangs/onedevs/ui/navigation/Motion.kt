package com.devbangs.onedevs.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.devbangs.onedevs.ui.theme.oneDevsColors

// How the app moves between screens: it doesn't. The next screen is simply
// there. Every animated version -- cross-fades, fade-through, slides -- had
// one screen cropping or showing through the other while it moved, and an
// instant change never does.

val NavEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
val NavExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { ExitTransition.None }
val NavPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
val NavPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { ExitTransition.None }

/** A destination drawn on the page colour, so no other screen ever shows through it. */
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
