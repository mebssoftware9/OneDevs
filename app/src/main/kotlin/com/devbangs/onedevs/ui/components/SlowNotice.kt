package com.devbangs.onedevs.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R

/**
 * DevBot mentioning that something is taking a while.
 *
 * Shown from measured request time, so it appears when there has actually been
 * a wait rather than when the system dislikes the look of the connection. It
 * leaves on its own: there is nothing to decide, so there is nothing to
 * dismiss.
 */
@Composable
fun SlowNotice(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            DevBotMark(size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.slow_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}
