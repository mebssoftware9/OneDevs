package com.devbangs.onedevs.ui.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Wide enough for two lines of requirement at 10sp, narrow enough that four sit
 * across a 360dp screen. FlowRow wraps them, so a wider screen gets more per
 * row rather than more space between them.
 */
private val CellWidth = 74.dp

/**
 * One group of badges, as a card.
 *
 * A hairline on the page rather than a filled card. Five filled cards stacked
 * turn the screen into a column of slabs, and the page is pure white by
 * decision -- the border is what separates them without tinting anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeGroupCard(
    group: BadgeGroup,
    earned: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val accent = group.accent(oneDevsColors)
    val held = group.badges.count { it.name in earned }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.solid),
            ) {
                Icon(
                    painter = painterResource(group.icon),
                    contentDescription = null,
                    tint = accent.onSolid,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(group.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(group.tagline),
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.badge_progress, held, group.badges.size),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = accent.solid,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.tint)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            group.badges.forEach { badge ->
                BadgeCell(badge, accent, badge.name in earned)
            }
        }
    }
}

/**
 * A single badge. Locked is the common case and has to stay legible as one:
 * the glyph keeps its shape and loses its colour, so an unearned badge still
 * reads as the thing it will become rather than as a blank.
 */
@Composable
private fun BadgeCell(badge: BadgeSpec, accent: Accent, earned: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CellWidth),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(if (earned) accent.tint else scheme.surfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(badge.icon),
                    contentDescription = null,
                    tint = if (earned) accent.solid else scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (earned) accent.solid else scheme.surfaceContainerHighest),
            ) {
                Icon(
                    painter = painterResource(
                        if (earned) R.drawable.ic_check else R.drawable.ic_lock,
                    ),
                    contentDescription = stringResource(
                        if (earned) R.string.cd_badge_earned else R.string.cd_badge_locked,
                    ),
                    tint = if (earned) accent.onSolid else scheme.onSurfaceVariant,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(badge.name),
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (earned) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(badge.requirement),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
