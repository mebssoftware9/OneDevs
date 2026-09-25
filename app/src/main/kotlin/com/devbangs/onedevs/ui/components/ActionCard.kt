package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent

/**
 * Wide enough for two lines of title and three of body at bodySmall, narrow
 * enough that the next card is visibly cut off. A row of three that exactly
 * fills the screen looks like the whole set even when it isn't.
 *
 * 210dp rather than 232dp: these are a way into the screen, not the screen, and
 * at the wider size they were taking the room My launches needed underneath.
 * The paddings inside are tuned against the same budget -- the row is 199dp
 * tall, and every point of that is a point My launches does not start at.
 */
internal val ActionCardWidth = 210.dp

/**
 * One thing a developer can do with an app.
 *
 * Each action wears its own [accent] rather than the house blue. They are
 * different products with different costs -- one is free, one spends DevCoins,
 * one needs a published app -- and telling them apart at a glance is worth more
 * here than a uniform row. The accents are generated against a contrast floor,
 * so a distinct identity never costs an unreadable label.
 *
 * [available] changes the pill, not the palette. A greyed-out card and a live
 * card at the same weight is how someone taps the wrong one, but draining the
 * colour out of a locked action also hides which action it was.
 */
@Composable
fun ActionCard(
    accent: Accent,
    icon: Painter,
    title: String,
    body: String,
    status: String,
    available: Boolean,
    footerIcon: Painter,
    footerLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(ActionCardWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(accent.tint)
            .clickable(enabled = available, onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.solid),
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = accent.onSolid,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(accent.solid)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            if (!available) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = accent.onSolid,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = accent.onSolid,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Pushes the footer down so it lands on one line across all three cards,
        // whatever their body length.
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = footerIcon,
                    contentDescription = null,
                    tint = accent.solid,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = footerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.solid,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.solid),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = accent.onSolid,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
