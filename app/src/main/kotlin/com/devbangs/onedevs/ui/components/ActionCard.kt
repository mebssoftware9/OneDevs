package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent

/**
 * One way into the Launch screen, sized to sit two-across without scrolling.
 *
 * There is no fixed width any more: two of these share the row, so each takes
 * half of whatever the screen gives. That is 110dp of usable space inside the
 * padding on a 320dp phone, which is what every size below is chosen against.
 *
 * The status is coloured text rather than a filled pill. At 110dp the longest
 * of them needs 106, and a pill's own padding pushes that to 124 -- so the pill
 * was a shape that could not hold its own contents on a small phone. Losing it
 * also loses a row of vertical padding, which is the other thing a card this
 * narrow cannot spare.
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
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.tint)
            .clickable(enabled = available, onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.solid),
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = accent.onSolid,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 17.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!available) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = accent.solid,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.Medium,
                color = accent.solid,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Spacer(Modifier.weight(1f))
        // No arrow disc. A 32dp circle was a quarter of the width of a card
        // this size, spent saying "tappable" about a card that is entirely a
        // button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                painter = footerIcon,
                contentDescription = null,
                tint = accent.solid,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = footerLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium,
                color = accent.solid,
            )
        }
    }
}
