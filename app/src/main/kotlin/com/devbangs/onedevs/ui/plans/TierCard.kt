package com.devbangs.onedevs.ui.plans

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.accentFeedbackLight
import com.devbangs.onedevs.ui.theme.accentMissionLight
import com.devbangs.onedevs.ui.theme.accentTestingLight
import com.devbangs.onedevs.ui.theme.brandNavy
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * How one plan's card is dressed.
 *
 * The paid cards keep their own colour in both themes: a plan is something
 * you buy, and it should look the same at night as it does by day. Community
 * takes the page's colours and the live accent.
 */
@Immutable
internal class TierStyle(
    /** The card. */
    val fill: Brush,
    /** Name, price, features. */
    val ink: Color,
    /** Tagline, the period, the rule, a button that is switched off. */
    val muted: Color,
    /** The plan's mark and the tick beside each feature. */
    val mark: Color,
    /** Behind the mark, the bonus, and a button that is switched off. */
    val wash: Color,
    /** The button and the badge. */
    val accent: Color,
    /** Whatever sits on [accent]. */
    val onAccent: Color,
)

/** A gold that reads on navy and carries navy text: Pro's crown, ticks and button. */
private val ProGold = Color(0xFFE8B84A)

private val PaidInk = Color.White
private val PaidMuted = Color.White.copy(alpha = 0.78f)
private val PaidWash = Color.White.copy(alpha = 0.16f)

/** Violet into indigo, the mission and feedback accents. White clears 6:1 on both ends. */
internal val PremiumStyle = TierStyle(
    fill = Brush.linearGradient(listOf(accentMissionLight.solid, accentFeedbackLight.solid)),
    ink = PaidInk,
    muted = PaidMuted,
    mark = PaidInk,
    wash = PaidWash,
    accent = PaidInk,
    onAccent = accentMissionLight.solid,
)

/** The icon's navy into the testing blue, with gold for what only Pro has. */
internal val ProStyle = TierStyle(
    fill = Brush.linearGradient(listOf(brandNavy, accentTestingLight.solid)),
    ink = PaidInk,
    muted = PaidMuted,
    mark = ProGold,
    wash = PaidWash,
    accent = ProGold,
    onAccent = brandNavy,
)

@Composable
internal fun communityStyle(): TierStyle {
    val live = oneDevsColors.live
    val scheme = MaterialTheme.colorScheme
    return TierStyle(
        fill = SolidColor(live.tint),
        ink = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        mark = live.solid,
        wash = oneDevsColors.card,
        accent = live.solid,
        onAccent = live.onSolid,
    )
}

/** One plan: its mark and name, the price, what it gives, and the way to it. */
@Composable
internal fun TierCard(
    style: TierStyle,
    mark: Painter,
    name: String,
    tagline: String,
    price: String,
    features: List<String>,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
    period: String? = null,
    badge: String? = null,
    bonus: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(style.fill)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(style.wash),
            ) {
                Icon(
                    painter = mark,
                    contentDescription = null,
                    tint = style.mark,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = style.ink,
                )
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = style.muted,
                )
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = style.onAccent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(style.accent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        Row {
            Text(
                text = price,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = style.ink,
                modifier = Modifier.alignByBaseline(),
            )
            if (period != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = period,
                    style = MaterialTheme.typography.titleSmall,
                    color = style.muted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        if (bonus != null) Bonus(bonus, style)

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(style.muted.copy(alpha = 0.24f)),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            features.forEach { Feature(it, style.mark, style.ink) }
        }

        Text(
            text = action,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = if (enabled) style.onAccent else style.muted,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(if (enabled) style.accent else style.wash)
                .clickable(enabled = enabled, role = Role.Button, onClick = onAction)
                .padding(vertical = 15.dp),
        )
    }
}

/** The DevCoins a paid plan adds, on the coin itself. */
@Composable
private fun Bonus(text: String, style: TierStyle) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(style.wash)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_devcoin),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = style.ink,
        )
    }
}
