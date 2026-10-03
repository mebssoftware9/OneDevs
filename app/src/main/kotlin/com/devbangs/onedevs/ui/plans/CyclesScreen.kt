package com.devbangs.onedevs.ui.plans

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.ui.theme.oneDevsColors
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.plans.CycleAllowance
import com.devbangs.onedevs.data.plans.CycleStart
import com.devbangs.onedevs.data.plans.GhostRun
import com.devbangs.onedevs.data.plans.Insight
import com.devbangs.onedevs.data.plans.Tier
import com.devbangs.onedevs.data.plans.androidName
import com.devbangs.onedevs.data.plans.epochOf
import com.devbangs.onedevs.ui.components.BrandedLoading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often an open dashboard asks for new numbers. */
private const val POLL_MS = 30_000L

/**
 * Testing cycles: what Premium and Pro pay for.
 *
 * The cycles running now, each with its live dashboard and reports, and the
 * way to start the next one while the month still allows it. Nothing here
 * decides anything: the server checks the plan, the app and the month.
 */
@Composable
fun CyclesScreen(onPlans: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val plan by app.plans.plan.collectAsState()

    var runs by remember { mutableStateOf<List<GhostRun>?>(null) }
    var allowance by remember { mutableStateOf<CycleAllowance?>(null) }
    var failed by remember { mutableStateOf(false) }
    var skew by remember { mutableLongStateOf(0L) }
    var visible by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Said?>(null) }
    var starting by remember { mutableStateOf(false) }

    suspend fun load() {
        val fresh = quietly(quiet = runs != null) { app.ghostline.runs() }
        quietly { app.ghostline.allowance() }?.let { allowance = it }
        if (fresh == null) {
            failed = runs == null
            return
        }
        failed = false
        runs = fresh.filter { it.isCycle }
        fresh.firstOrNull()?.let { run ->
            epochOf(run.serverNow)?.let { skew = it - System.currentTimeMillis() }
        }
    }

    LifecycleResumeEffect(Unit) {
        visible = true
        onPauseOrDispose { visible = false }
    }
    LaunchedEffect(visible) {
        while (visible) {
            load()
            delay(POLL_MS)
        }
    }

    val list = runs
    if (list == null) {
        if (failed) {
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Note(stringResource(R.string.cy_offline))
            }
        } else {
            BrandedLoading(modifier)
        }
        return
    }

    // The server's allowance is the fresher answer; the cached plan covers
    // the moment before it arrives.
    val paid = (allowance?.apps ?: 0) > 0 || plan?.tier?.let { it != Tier.Community } == true
    val live = list.filter { it.live }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.cy_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        note?.let { said ->
            Note(said.arg?.let { stringResource(said.text, it) } ?: stringResource(said.text))
        }

        live.forEach { Dashboard(it, skew) }

        if (paid) {
            val listings by app.listings.listings.collectAsState(initial = emptyList())
            val running = live.map { it.listing }.toSet()
            val eligible = listings.filter { it.channel == Channel.Testing && it.id !in running }
            StartCycle(
                hasRuns = live.isNotEmpty(),
                allowance = allowance,
                apps = eligible.map { it.id to it.title },
                starting = starting,
                onStart = { listingId ->
                    starting = true
                    note = null
                    scope.launch {
                        val outcome = app.ghostline.startCycle(listingId)
                        starting = false
                        note = startText(context, outcome)
                        if (outcome == CycleStart.Started) load()
                    }
                },
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(oneDevsColors.card, RoundedCornerShape(20.dp))
                    .padding(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.cy_plan_needed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActionButton(
                    text = stringResource(R.string.cy_see_plans),
                    enabled = true,
                    filled = true,
                    onClick = onPlans,
                )
            }
        }

        val past = list.filterNot { it.live }
        if (past.isNotEmpty()) {
            Text(
                text = stringResource(R.string.cy_past),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            past.forEach { PastRun(it) }
        }

        Text(
            text = stringResource(R.string.cy_fine_print),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A line to show, and the one value it may carry. */
private data class Said(@androidx.annotation.StringRes val text: Int, val arg: String? = null)

/** What to say after asking to start a cycle. */
private fun startText(context: android.content.Context, outcome: CycleStart): Said = when (outcome) {
    CycleStart.Started -> Said(R.string.cy_started)
    CycleStart.Unreachable -> Said(R.string.cy_err_offline)
    is CycleStart.Refused -> when (outcome.reason) {
        "already_running" -> Said(R.string.cy_err_running)
        "not_your_testing_app" -> Said(R.string.cy_err_app)
        "no_plan" -> Said(R.string.cy_err_plan)
        "month_used" -> epochOf(outcome.nextAt)?.let {
            Said(R.string.cy_err_month_until, dateOf(context, it))
        } ?: Said(R.string.cy_err_month)
        else -> Said(R.string.cy_err_offline)
    }
}

private fun dateOf(context: android.content.Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

/** Choosing the app, while the month still has a cycle left. */
@Composable
private fun StartCycle(
    hasRuns: Boolean,
    allowance: CycleAllowance?,
    apps: List<Pair<String, String>>,
    starting: Boolean,
    onStart: (String) -> Unit,
) {
    val context = LocalContext.current
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = chosen?.takeIf { id -> apps.any { it.first == id } } ?: apps.firstOrNull()?.first
    val scheme = MaterialTheme.colorScheme
    val left = allowance?.left
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(oneDevsColors.card, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(
            text = stringResource(if (hasRuns) R.string.cy_another else R.string.cy_how_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!hasRuns) {
            listOf(
                stringResource(R.string.cy_how_1),
                stringResource(R.string.cy_how_2, allowance?.needed ?: 16),
                stringResource(R.string.cy_how_3),
                stringResource(R.string.cy_how_4),
            ).forEach { Feature(it, scheme.primary, scheme.onSurface) }
        }
        if (allowance != null) {
            Text(
                text = stringResource(R.string.cy_left, allowance.left, allowance.apps),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        }
        when {
            left == 0 -> Text(
                text = epochOf(allowance.nextAt)?.let {
                    stringResource(R.string.cy_err_month_until, dateOf(context, it))
                } ?: stringResource(R.string.cy_err_month),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            apps.isEmpty() -> Text(
                text = stringResource(R.string.cy_no_app),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    text = stringResource(R.string.gl_choose),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                apps.forEach { (id, title) ->
                    val on = id == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) oneDevsColors.brandTint else oneDevsColors.well)
                            .clickable { chosen = id }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                ActionButton(
                    text = stringResource(if (starting) R.string.cy_starting else R.string.cy_start),
                    enabled = selected != null && !starting,
                    filled = true,
                    onClick = { selected?.let(onStart) },
                )
            }
        }
    }
}

/** The reports every fourth day, newest first. Counts and phones, never names. */
@Composable
internal fun Insights(run: GhostRun) {
    val scheme = MaterialTheme.colorScheme
    Section(stringResource(R.string.cy_insights)) {
        if (run.insights.isEmpty()) {
            Text(
                text = stringResource(R.string.cy_insights_none),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        run.insights.forEachIndexed { index, insight ->
            InsightCard(insight, run.needed, expanded = index == 0)
        }
    }
}

@Composable
private fun InsightCard(insight: Insight, needed: Int, expanded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(insight.day) { mutableStateOf(expanded) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(oneDevsColors.well, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.cy_insight_title, insight.day),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${insight.testers}/$needed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
            )
        }
        if (open) {
            Figure(stringResource(R.string.cy_insight_testers), "${insight.testers}/$needed")
            Figure(stringResource(R.string.cy_insight_new), insight.newTesters.toString())
            Figure(stringResource(R.string.cy_insight_minutes), minutes(insight.avgSeconds))
            Figure(stringResource(R.string.cy_insight_one_day), insight.oneDay.toString())
            if (insight.models.isNotEmpty()) {
                Figure(
                    stringResource(R.string.cy_insight_phones),
                    insight.models.joinToString(", ") { "${it.model} (${it.n})" },
                )
            }
            if (insight.android.isNotEmpty()) {
                Figure(
                    stringResource(R.string.gl_versions),
                    insight.android
                        .map { stringResource(R.string.gl_android, androidName(it.sdk)) + " (${it.n})" }
                        .joinToString(", "),
                )
            }
        }
    }
}

/** Whole minutes, with one decimal under ten so a short session is not "0". */
private fun minutes(seconds: Int): String {
    val m = seconds / 60.0
    return if (m < 10) String.format(java.util.Locale.getDefault(), "%.1f", m) else m.toInt().toString()
}

@Composable
private fun Figure(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
