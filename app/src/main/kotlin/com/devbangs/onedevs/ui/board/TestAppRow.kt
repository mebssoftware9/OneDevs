package com.devbangs.onedevs.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors

/** An app on the board, as the list needs it. */
data class BoardApp(
    val name: String,
    val category: String,
    val rating: String,
    val size: String,
    val reward: Int,
    val icon: Int,
    val accent: Int,
)

/**
 * One app waiting for testers.
 *
 * Play puts rating and size on one metadata line under the name, and that is
 * the right call on a phone: the mockup's separate columns for star, size and
 * reward leave about 100dp for the name, which is not enough for one. Only the
 * reward keeps its own place on the right, because it is the reason to tap.
 */
@Composable
fun TestAppRow(
    app: BoardApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = listOf(
        oneDevsColors.testing, oneDevsColors.mission, oneDevsColors.live,
        oneDevsColors.feedback, oneDevsColors.community,
    )
    val accent: Accent = accents[app.accent % accents.size]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(accent.solid),
        ) {
            Icon(
                painter = painterResource(app.icon),
                contentDescription = null,
                tint = accent.onSolid,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_star_fill),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = stringResource(R.string.board_app_meta, app.rating, app.size, app.category),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // No chevron. The whole row is the target, and Play's own lists carry
        // none -- an arrow at the end of every row is a hint nobody needed
        // twice, taking width from the name that did. The coin says what the
        // tap is for, which the arrow never did.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(oneDevsColors.brandTint)
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_coins),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.board_reward, app.reward, app.reward),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                maxLines = 1,
            )
        }
    }
}
