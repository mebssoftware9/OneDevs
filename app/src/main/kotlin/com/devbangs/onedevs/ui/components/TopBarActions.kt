package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors

/** The chip's drawn height, set against the wordmark rather than picked. */
private val ChipHeight = 38.dp

/**
 * The size every bare glyph in the app is drawn at, top bar and navigation bar
 * alike. NavigationBarItem draws its icon at 24dp, so anything up here that is
 * a glyph and not a control has to be 24dp too, or the two ends of the screen
 * stop looking like one app.
 */
private val GlyphSize = 24.dp

/**
 * Balance over its own label, so the number is never a bare digit the user has
 * to infer a unit for. clickable merges the two lines into one node, so this
 * reads out as "0 DevCoins, button" rather than announcing the count twice.
 *
 * The backing is the brand tint, not a neutral. surfaceContainerHigh made the
 * one persistent control in the app a grey slab sitting on a blue brand, and
 * grey is what a control looks like when nobody chose its colour.
 */
@Composable
fun DevCoinChip(
    balance: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // 38dp is a drawn height, not a touched one. This holds the measured
            // height at the 48dp minimum without drawing the chip that tall.
            .minimumInteractiveComponentSize()
            .height(ChipHeight)
            .clip(CircleShape)
            .background(oneDevsColors.brandTint)
            .clickable(onClick = onClick)
            .padding(start = 5.dp, end = 13.dp),
    ) {
        // No circular clip. The artwork is already a coin, and clipping it to a
        // circle shaved the raised rim off its own edge.
        Image(
            painter = painterResource(R.drawable.ic_devcoin),
            contentDescription = null,
            modifier = Modifier.size(GlyphSize),
        )
        Spacer(Modifier.width(7.dp))
        Column(verticalArrangement = Arrangement.Center) {
            // Both line heights are tightened from the type scale's defaults:
            // stacked at their natural leading the pair overflows the chip.
            Text(
                text = balance.toString(),
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 16.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.devcoin_label),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same tint and the same height as the chip beside it, so the two read as
 * one pair of controls rather than a control and a loose glyph. The circle is
 * back: it was never the container that was wrong, it was that the container
 * was grey.
 */
@Composable
fun ProfileAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(ChipHeight)
            .clip(CircleShape)
            .background(oneDevsColors.brandTint)
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_user_circle),
            contentDescription = stringResource(R.string.cd_profile),
            // onSurface, matching the mockup. On the tint a muted glyph washes
            // out; the navigation bar's own glyphs sit on the bare page, which
            // is a different problem with a different answer.
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
