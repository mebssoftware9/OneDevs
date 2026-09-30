package com.devbangs.onedevs.ui.plans

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.plans.Tier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The plan, at the top of Profile: the next step up, said in numbers.
 *
 * Community sees Premium, Premium sees Pro, and Pro sees its own plan rather
 * than an offer for something it already has. The whole card is one button
 * to the Plans screen, where the buying happens.
 */
@Composable
fun PlanPromo(onPlans: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val plan by app.plans.plan.collectAsState()
    val offers by app.billing.offers.collectAsState()
    LaunchedEffect(Unit) {
        if (app.billing.offers.value.premium == null) app.billing.loadOffers()
    }
    val perMonth = stringResource(R.string.tier_per_month)

    when (plan?.tier ?: Tier.Community) {
        Tier.Community -> Promo(
            style = PremiumStyle,
            mark = R.drawable.ic_sparkle_fill,
            title = stringResource(R.string.promo_premium_title),
            body = stringResource(R.string.promo_premium_body),
            action = stringResource(R.string.tier_get_premium),
            price = (offers.premium ?: stringResource(R.string.tier_premium_price)) + " " + perMonth,
            onClick = onPlans,
            modifier = modifier,
        )
        Tier.Premium -> Promo(
            style = ProStyle,
            mark = R.drawable.ic_crown_fill,
            title = stringResource(R.string.promo_pro_title),
            body = stringResource(R.string.promo_pro_body),
            action = stringResource(R.string.tier_get_pro),
            price = (offers.pro ?: stringResource(R.string.tier_pro_price)) + " " + perMonth,
            onClick = onPlans,
            modifier = modifier,
        )
        Tier.Pro -> Promo(
            style = ProStyle,
            mark = R.drawable.ic_crown_fill,
            title = stringResource(R.string.tier_you_are_on, stringResource(R.string.plan_pro)),
            body = stringResource(R.string.promo_on_pro_body),
            action = stringResource(R.string.plans_current),
            price = null,
            onClick = onPlans,
            modifier = modifier,
        )
    }
}

@Composable
private fun Promo(
    style: TierStyle,
    mark: Int,
    title: String,
    body: String,
    action: String,
    price: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(style.fill)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        TesterArc(style.mark, Modifier.matchParentSize())
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(style.wash),
                ) {
                    Icon(
                        painter = painterResource(mark),
                        contentDescription = null,
                        tint = style.mark,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Clear of the arc in the corner.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = style.ink,
                    modifier = Modifier.padding(end = 40.dp),
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = style.muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = style.onAccent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(style.accent)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
                Spacer(Modifier.weight(1f))
                if (price != null) {
                    Text(
                        text = price,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = style.ink,
                    )
                }
            }
        }
    }
}

/** A corner of the tester ring from the icon and the store graphic, in the plan's own colour. */
@Composable
private fun TesterArc(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val centre = Offset(size.width - 10.dp.toPx(), 10.dp.toPx())
        val r = 56.dp.toPx()
        val halo = 8.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.16f), r, centre, style = Stroke(width = 1.5.dp.toPx()))
        repeat(12) { k ->
            val a = (k * 30 - 90) * PI / 180
            val p = Offset(centre.x + r * cos(a).toFloat(), centre.y + r * sin(a).toFloat())
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f), Color.Transparent), p, halo), halo, p)
            drawCircle(color.copy(alpha = 0.92f), 3.dp.toPx(), p)
        }
    }
}
