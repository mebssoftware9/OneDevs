package com.devbangs.onedevs.ui.plans

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.plans.PlanLaunch
import com.devbangs.onedevs.data.plans.Products
import com.devbangs.onedevs.data.plans.PurchaseOutcome
import com.devbangs.onedevs.data.plans.Tier
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.launch

/**
 * Community, Premium and Pro, from free to the top.
 *
 * Prices come from Play, in the person's own currency; until Play answers, a
 * paid card shows the US price its plan was set at. Nothing unlocks here: a
 * purchase goes to the server, which asks Google, and the plan changes when
 * it says yes.
 */
@Composable
fun PlansScreen(onCycles: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val plan by app.plans.plan.collectAsState()
    val offers by app.billing.offers.collectAsState()
    var note by remember { mutableStateOf<Int?>(null) }
    // The plan whose Play sheet is opening, so one press cannot open two.
    var opening by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.plans.refresh()
        app.billing.loadOffers()
    }
    LaunchedEffect(Unit) {
        app.billing.outcomes.collect { outcome ->
            opening = null
            note = if (outcome == PurchaseOutcome.ProActive) R.string.tier_done else outcomeText(outcome)
        }
    }

    fun buy(productId: String) {
        val activity = context.findActivity() ?: return
        opening = productId
        note = null
        scope.launch {
            val launched = app.billing.buyPlan(activity, productId)
            if (launched != PlanLaunch.Opened) {
                opening = null
                note = if (launched == PlanLaunch.OnOtherPlan) R.string.tier_other_plan else R.string.plans_unavailable
            }
        }
    }

    val tier = plan?.tier ?: Tier.Community
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.tier_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.tier_subheading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CurrentTier(tier)
        note?.let { Note(stringResource(it)) }
        // What a paid plan is for: its testing cycles, one tap away.
        if (tier != Tier.Community) {
            ActionButton(
                text = stringResource(R.string.tier_cycles),
                enabled = true,
                filled = true,
                onClick = onCycles,
            )
            // Cancelling is one tap from where subscribing was, as Google
            // asks: Play's own page for this subscription.
            val uri = LocalUriHandler.current
            ActionButton(
                text = stringResource(R.string.tier_manage),
                enabled = true,
                filled = false,
                onClick = {
                    val sku = if (tier == Tier.Pro) Products.PRO else Products.PREMIUM
                    uri.openUri("https://play.google.com/store/account/subscriptions?sku=$sku&package=${context.packageName}")
                },
            )
        }

        TierCard(
            style = communityStyle(),
            mark = painterResource(R.drawable.ic_users_three),
            name = stringResource(R.string.plan_community),
            tagline = stringResource(R.string.tier_community_tagline),
            price = stringResource(R.string.tier_free),
            features = listOf(
                stringResource(R.string.tier_community_1),
                stringResource(R.string.tier_community_2),
                stringResource(R.string.tier_community_3),
                stringResource(R.string.tier_community_4),
                stringResource(R.string.tier_community_5),
            ),
            action = stringResource(if (tier == Tier.Community) R.string.plans_current else R.string.plans_included),
            enabled = false,
            onAction = {},
        )

        TierCard(
            style = PremiumStyle,
            mark = painterResource(R.drawable.ic_sparkle_fill),
            name = stringResource(R.string.plan_premium),
            tagline = stringResource(R.string.tier_premium_tagline),
            price = offers.premium ?: stringResource(R.string.tier_premium_price),
            period = stringResource(R.string.tier_per_month),
            badge = stringResource(R.string.tier_popular),
            bonus = stringResource(R.string.tier_premium_bonus),
            features = listOf(
                stringResource(R.string.tier_premium_1),
                stringResource(R.string.tier_premium_2),
                stringResource(R.string.tier_premium_3),
                stringResource(R.string.tier_premium_4),
                stringResource(R.string.tier_premium_5),
                stringResource(R.string.tier_premium_6),
                stringResource(R.string.tier_premium_7),
                stringResource(R.string.tier_premium_8),
                stringResource(R.string.tier_premium_9),
                stringResource(R.string.tier_premium_10),
                stringResource(R.string.tier_priority_support),
            ),
            action = stringResource(
                when {
                    tier == Tier.Premium -> R.string.plans_current
                    tier == Tier.Pro -> R.string.plans_included
                    opening == Products.PREMIUM -> R.string.plans_opening
                    else -> R.string.tier_get_premium
                },
            ),
            enabled = tier == Tier.Community && opening == null,
            onAction = { buy(Products.PREMIUM) },
        )

        TierCard(
            style = ProStyle,
            mark = painterResource(R.drawable.ic_crown_fill),
            name = stringResource(R.string.plan_pro),
            tagline = stringResource(R.string.tier_pro_tagline),
            price = offers.pro ?: stringResource(R.string.tier_pro_price),
            period = stringResource(R.string.tier_per_month),
            bonus = stringResource(R.string.tier_pro_bonus),
            features = listOf(
                stringResource(R.string.tier_pro_1),
                stringResource(R.string.tier_pro_2),
                stringResource(R.string.tier_pro_3),
                stringResource(R.string.tier_pro_4),
                stringResource(R.string.tier_priority_support),
            ),
            action = stringResource(
                when {
                    tier == Tier.Pro -> R.string.plans_current
                    opening == Products.PRO -> R.string.plans_opening
                    else -> R.string.tier_get_pro
                },
            ),
            enabled = tier != Tier.Pro && opening == null,
            onAction = { buy(Products.PRO) },
        )

        Text(
            text = stringResource(R.string.tier_guarantee),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.tier_fine_print),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The plan this account is on, in that plan's colour. */
@Composable
private fun CurrentTier(tier: Tier) {
    val accent = when (tier) {
        Tier.Community -> oneDevsColors.live
        Tier.Premium -> oneDevsColors.mission
        Tier.Pro -> oneDevsColors.testing
    }
    Text(
        text = stringResource(R.string.tier_you_are_on, stringResource(nameOf(tier))),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = accent.solid,
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.tint)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@StringRes
private fun nameOf(tier: Tier): Int = when (tier) {
    Tier.Community -> R.string.plan_community
    Tier.Premium -> R.string.plan_premium
    Tier.Pro -> R.string.plan_pro
}
