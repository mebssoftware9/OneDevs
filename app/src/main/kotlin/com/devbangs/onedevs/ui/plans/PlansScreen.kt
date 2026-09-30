package com.devbangs.onedevs.ui.plans

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.plans.Products
import com.devbangs.onedevs.data.plans.PurchaseOutcome
import com.devbangs.onedevs.data.plans.epochOf
import com.devbangs.onedevs.ui.components.FilterPills
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** The Activity a Compose context belongs to; Play's sheet needs one. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Ink on the Ghostline card, which is dark in both themes. */
internal val GhostInk = Color.White
internal val GhostMuted = Color.White.copy(alpha = 0.72f)
internal val GhostWash = Color.White.copy(alpha = 0.12f)

/**
 * Free, Lab Pro and Ghostline, side by side.
 *
 * Prices come from Play, in the person's own currency. Nothing is unlocked
 * from this screen: a purchase is sent to the server, which asks Google, and
 * the plan changes when it says yes.
 */
@Composable
fun PlansScreen(onGhostline: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val plan by app.plans.plan.collectAsState()
    val offers by app.billing.offers.collectAsState()
    var yearly by rememberSaveable { mutableStateOf(true) }
    var note by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.plans.refresh()
        app.billing.loadOffers()
    }
    LaunchedEffect(Unit) {
        app.billing.outcomes.collect { outcome ->
            busy = false
            note = outcomeText(outcome)
        }
    }

    val pro = plan?.pro == true
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.plans_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.plans_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CurrentPlan(pro = pro, until = plan?.proUntil, source = plan?.proSource)
        note?.let { Note(stringResource(it)) }

        PlanCard(
            title = stringResource(R.string.plans_free),
            price = stringResource(R.string.plans_free_price),
            features = listOf(
                stringResource(R.string.plans_free_1),
                stringResource(R.string.plans_free_2),
                stringResource(R.string.plans_free_3),
            ),
            action = stringResource(if (pro) R.string.plans_included else R.string.plans_current),
            enabled = false,
            onAction = {},
        )

        PlanCard(
            title = stringResource(R.string.plans_pro),
            price = (if (yearly) offers.yearly else offers.monthly)?.let {
                stringResource(if (yearly) R.string.plans_per_year else R.string.plans_per_month, it)
            } ?: "–",
            features = listOf(
                stringResource(R.string.plans_pro_1),
                stringResource(R.string.plans_pro_2),
                stringResource(R.string.plans_pro_3),
            ),
            highlight = true,
            header = {
                FilterPills(
                    labels = listOf(stringResource(R.string.plans_monthly), stringResource(R.string.plans_yearly)),
                    selected = if (yearly) 1 else 0,
                    onSelect = { yearly = it == 1 },
                    fillWidth = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            action = stringResource(
                when {
                    pro -> R.string.plans_have_pro
                    busy -> R.string.plans_opening
                    else -> R.string.plans_upgrade
                },
            ),
            enabled = !pro && !busy,
            onAction = {
                val activity = context.findActivity() ?: return@PlanCard
                busy = true
                note = null
                scope.launch {
                    val opened = app.billing.buyPro(activity, if (yearly) Products.YEARLY else Products.MONTHLY)
                    if (!opened) {
                        busy = false
                        note = R.string.plans_unavailable
                    }
                }
            },
        )

        GhostlineCard(price = offers.ghostline, onStart = onGhostline)

        Text(
            text = stringResource(R.string.plans_fine_print),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun outcomeText(outcome: PurchaseOutcome): Int = when (outcome) {
    PurchaseOutcome.ProActive -> R.string.plans_done_pro
    PurchaseOutcome.GhostlineStarted -> R.string.gl_started
    PurchaseOutcome.Pending -> R.string.plans_pending
    PurchaseOutcome.Cancelled -> R.string.plans_cancelled
    PurchaseOutcome.Unconfirmed -> R.string.plans_unconfirmed
    is PurchaseOutcome.Refused -> when (outcome.reason) {
        "already_running" -> R.string.gl_err_running
        "not_your_testing_app" -> R.string.gl_err_app
        else -> R.string.plans_refused
    }
    PurchaseOutcome.Unavailable -> R.string.plans_unavailable
}

@Composable
private fun CurrentPlan(pro: Boolean, until: String?, source: String?) {
    val accent = if (pro) oneDevsColors.live else oneDevsColors.testing
    val locale = LocalConfiguration.current.locales[0]
    val date = epochOf(until)?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(it)) }
    Text(
        text = when {
            !pro -> stringResource(R.string.plans_on_free)
            source == "ghostline" && date != null -> stringResource(R.string.plans_on_pro_ghostline, date)
            date != null -> stringResource(R.string.plans_on_pro_until, date)
            else -> stringResource(R.string.plans_have_pro)
        },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = accent.solid,
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.tint)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun PlanCard(
    title: String,
    price: String,
    features: List<String>,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
    highlight: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (highlight) 2.dp else 1.dp,
                color = if (highlight) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = price,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) scheme.primary else scheme.onSurface,
            )
        }
        header?.invoke()
        features.forEach { Feature(it, scheme.primary, scheme.onSurface) }
        ActionButton(
            text = action,
            enabled = enabled,
            filled = highlight,
            onClick = onAction,
        )
    }
}

@Composable
internal fun Feature(text: String, tick: Color, ink: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = tick,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = ink)
    }
}

@Composable
internal fun ActionButton(text: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = when {
            !enabled -> scheme.onSurfaceVariant
            filled -> scheme.onPrimary
            else -> scheme.primary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (enabled && filled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/** Ghostline's card: dark in both themes, like the thing it is named after. */
@Composable
private fun GhostlineCard(price: String?, onStart: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneDevsColors.brandNavy)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.gl_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = GhostInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = price?.let { stringResource(R.string.gl_per_run, it) } ?: "–",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = GhostInk,
            )
        }
        Text(
            text = stringResource(R.string.gl_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = GhostMuted,
        )
        listOf(
            stringResource(R.string.gl_f_first),
            stringResource(R.string.gl_f_boost),
            stringResource(R.string.gl_f_dashboard),
            stringResource(R.string.gl_f_hidden),
            stringResource(R.string.gl_f_lab),
            stringResource(R.string.gl_f_guarantee),
        ).forEach { Feature(it, GhostInk, GhostInk) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(GhostInk)
                .clickable(onClick = onStart)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.gl_start),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = oneDevsColors.brandNavy,
            )
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
    )
}
