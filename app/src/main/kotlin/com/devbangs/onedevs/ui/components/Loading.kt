package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Waiting, in the brand's blue.
 *
 * A turning coin belonged in a game. This is the same indicator every serious
 * app uses, which is the point: nobody should be looking at it.
 */
@Composable
fun Waiting(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    stroke: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CircularProgressIndicator(
        color = color,
        strokeWidth = stroke,
        modifier = modifier.size(size),
    )
}

/** The whole screen, while the app works out who you are. */
@Composable
fun BrandedLoading(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Waiting(size = 28.dp)
    }
}
