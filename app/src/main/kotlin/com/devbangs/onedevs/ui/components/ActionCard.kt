package com.devbangs.onedevs.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    // A card in its own colour, not a tint of it: the two ways to put an app
    // up are the reason this screen exists, so they carry the most weight on
    // it. The gradient deepens toward the foot, where the label sits.
    val shape = RoundedCornerShape(24.dp)
    val deep = lerp(accent.solid, Color.Black, 0.28f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .shadow(12.dp, shape, ambientColor = accent.solid, spotColor = accent.solid)
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.solid, deep)))
            .drawBehind {
                // A soft light in the top corner, so the colour has depth.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width, 0f),
                )
            }
            .clickable(enabled = available, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.2f)),
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = accent.onSolid,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(lineHeight = 19.sp),
            fontWeight = FontWeight.Bold,
            color = accent.onSolid,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (!available) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = accent.onSolid,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = accent.onSolid,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = accent.onSolid.copy(alpha = 0.86f),
        )
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                painter = footerIcon,
                contentDescription = null,
                tint = accent.onSolid,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = footerLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = accent.onSolid,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.onSolid),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_caret_right),
                    contentDescription = null,
                    tint = accent.solid,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
