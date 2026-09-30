package com.devbangs.onedevs.ui.plans

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.plans.GhostRun
import com.devbangs.onedevs.data.plans.Products
import com.devbangs.onedevs.data.plans.PurchaseOutcome
import com.devbangs.onedevs.data.plans.androidName
import com.devbangs.onedevs.data.plans.epochOf
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often an open dashboard asks for new numbers. */
private const val POLL_MS = 30_000L
private const val DAY_MS = 86_400_000L

/**
 * Ghostline: the run you are watching, or the way to start one.
 *
 * The countdown ticks every second from the server's clock, not the phone's:
 * the dashboard records the difference when it reads, so a phone set five
 * minutes fast does not end a test five minutes early.
 */
@Composable
fun GhostlineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()

    var runs by remember { mutableStateOf<List<GhostRun>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var skew by remember { mutableLongStateOf(0L) }
    var visible by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    var buying by remember { mutableStateOf(false) }

    suspend fun load() {
        val fresh = quietly(quiet = runs != null) { app.ghostline.runs() }
        if (fresh == null) {
            failed = runs == null
            return
        }
        failed = false
        runs = fresh
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
    LaunchedEffect(Unit) {
        app.billing.loadOffers()
        app.billing.outcomes.collect { outcome ->
            buying = false
            note = outcomeText(outcome)
            if (outcome == PurchaseOutcome.GhostlineStarted) load()
        }
    }

    val list = runs
    if (list == null) {
        if (failed) {
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Note(stringResource(R.string.gl_offline))
            }
        } else {
            BrandedLoading(modifier)
        }
        return
    }

    val live = list.filter { it.live }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.gl_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        note?.let { Note(stringResource(it)) }

        live.forEach { Dashboard(it, skew) }

        val listings by app.listings.listings.collectAsState(initial = emptyList())
        val running = live.map { it.listing }.toSet()
        val eligible = listings.filter { it.channel == Channel.Testing && it.id !in running }
        StartRun(
            hasRuns = live.isNotEmpty(),
            apps = eligible.map { it.id to it.title },
            price = app.billing.offers.collectAsState().value.ghostline,
            buying = buying,
            onBuy = { listingId ->
                val activity = context.findActivity() ?: return@StartRun
                buying = true
                note = null
                scope.launch {
                    if (!app.billing.buyGhostline(activity, listingId)) {
                        buying = false
                        note = R.string.plans_unavailable
                    }
                }
            },
        )

        val past = list.filterNot { it.live }
        if (past.isNotEmpty()) {
            Text(
                text = stringResource(R.string.gl_past),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            past.forEach { PastRun(it) }
        }

        Text(
            text = stringResource(R.string.gl_fine_print),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One live run or cycle: the countdown, the people, the phones. A cycle adds
 * its Spotlight and its reports every fourth day.
 */
@Composable
internal fun Dashboard(run: GhostRun, skew: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() + skew) }
    LaunchedEffect(run.id, skew) {
        while (true) {
            now = System.currentTimeMillis() + skew
            delay(1_000L)
        }
    }
    val start = epochOf(run.startedAt) ?: now
    val end = epochOf(run.endsAt) ?: now
    val left = (end - now).coerceAtLeast(0L)
    val span = (end - start).coerceAtLeast(1L)
    val done = ((now - start).toFloat() / span).coerceIn(0f, 1f)

    // The hero: dark, quiet, precise.
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(oneDevsColors.brandNavy)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunIcon(run, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = run.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GhostInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = GhostMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(
                    when {
                        run.state == "extended" -> R.string.gl_state_extended
                        run.isCycle -> R.string.cy_state_running
                        else -> R.string.gl_state_running
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = GhostInk,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GhostWash)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Text(
            text = stringResource(R.string.gl_time_left),
            style = MaterialTheme.typography.labelMedium,
            color = GhostMuted,
        )
        Countdown(left)

        // The whole run, with a mark every fourth day: a boost, and for a
        // cycle the start of a Spotlight.
        Box(Modifier.fillMaxWidth().height(10.dp)) {
            LinearProgressIndicator(
                progress = { done },
                strokeCap = StrokeCap.Round,
                color = GhostInk,
                trackColor = GhostWash,
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.fillMaxSize()) {
                val days = (span / DAY_MS).toInt().coerceAtLeast(1)
                repeat(days) { day ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (day > 0 && day % Products.GHOSTLINE_BOOST_EVERY == 0) {
                            Box(
                                Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(oneDevsColors.brandNavy),
                            )
                        }
                    }
                }
            }
        }
        Row {
            Text(
                text = stringResource(R.string.gl_day, ((now - start) / DAY_MS + 1).coerceIn(1, span / DAY_MS + 1).toInt(), (span / DAY_MS).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = GhostMuted,
                modifier = Modifier.weight(1f),
            )
            val spotlightEnds = epochOf(run.spotlightUntil)?.takeIf { run.spotlight && it > now }
            val nextSpotlight = epochOf(run.nextSpotlightAt)?.takeIf { it > now && it < end }
            when {
                !run.isCycle -> epochOf(run.nextBoostAt)?.takeIf { it > now && it < end }?.let { next ->
                    Text(
                        text = stringResource(R.string.gl_next_boost, short(next - now)),
                        style = MaterialTheme.typography.labelSmall,
                        color = GhostMuted,
                    )
                }
                spotlightEnds != null -> Text(
                    text = stringResource(R.string.cy_spotlight_now, short(spotlightEnds - now)),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GhostInk,
                )
                nextSpotlight != null -> Text(
                    text = stringResource(R.string.cy_spotlight_next, short(nextSpotlight - now)),
                    style = MaterialTheme.typography.labelSmall,
                    color = GhostMuted,
                )
                else -> Unit
            }
        }
    }

    // The numbers that decide it.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(
            value = "${run.testers}/${run.needed}",
            label = stringResource(R.string.gl_testers),
            progress = run.testers.toFloat() / run.needed.coerceAtLeast(1),
            good = run.testers >= run.needed,
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = run.activeToday.toString(),
            label = stringResource(R.string.gl_active_today),
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = run.missions.toString(),
            label = stringResource(R.string.gl_missions),
            modifier = Modifier.weight(1f),
        )
    }

    if (run.isCycle) Insights(run)
    if (run.daily.isNotEmpty()) Daily(run)
    if (run.byAndroid.isNotEmpty()) Versions(run)
    Installs(run, System.currentTimeMillis())
}

@Composable
private fun Countdown(millis: Long) {
    val total = millis / 1000
    val parts = listOf(
        total / 86_400 to stringResource(R.string.gl_unit_days),
        total % 86_400 / 3600 to stringResource(R.string.gl_unit_hours),
        total % 3600 / 60 to stringResource(R.string.gl_unit_minutes),
        total % 60 to stringResource(R.string.gl_unit_seconds),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { (value, unit) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GhostWash)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = value.toString().padStart(2, '0'),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = GhostInk,
                )
                Text(text = unit, style = MaterialTheme.typography.labelSmall, color = GhostMuted)
            }
        }
    }
}

/** "3 days", "5 hours", "8 minutes": enough to know when, not a second clock. */
@Composable
internal fun short(millis: Long): String {
    val m = (millis / 60_000).toInt()
    return when {
        m >= 1440 -> pluralStringResource(R.plurals.gl_days, m / 1440, m / 1440)
        m >= 60 -> pluralStringResource(R.plurals.gl_hours, m / 60, m / 60)
        else -> m.coerceAtLeast(1).let { pluralStringResource(R.plurals.gl_minutes, it, it) }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    good: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        progress?.let {
            LinearProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                strokeCap = StrokeCap.Round,
                color = if (good) oneDevsColors.live.solid else scheme.primary,
                trackColor = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

/** People who used the app, day by day. */
@Composable
private fun Daily(run: GhostRun) {
    val scheme = MaterialTheme.colorScheme
    val top = (run.daily.maxOf { it.testers }).coerceAtLeast(run.needed)
    Section(stringResource(R.string.gl_daily)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            run.daily.takeLast((if (run.isCycle) Products.CYCLE_DAYS else Products.GHOSTLINE_DAYS) + 7).forEach { day ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(day.testers.toFloat() / top)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (day.testers >= run.needed) oneDevsColors.live.solid else scheme.primary),
                )
            }
        }
    }
}

@Composable
private fun Versions(run: GhostRun) {
    val scheme = MaterialTheme.colorScheme
    val total = run.byAndroid.sumOf { it.second }.coerceAtLeast(1)
    Section(stringResource(R.string.gl_versions)) {
        run.byAndroid.forEach { (sdk, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.gl_android, androidName(sdk)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                )
                LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    strokeCap = StrokeCap.Round,
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).height(6.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = count.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Every phone the app reached, newest first. Models, never names. */
@Composable
private fun Installs(run: GhostRun, now: Long) {
    val scheme = MaterialTheme.colorScheme
    Section(stringResource(R.string.gl_installs)) {
        if (run.installs.isEmpty()) {
            Text(
                text = stringResource(R.string.gl_installs_none),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        run.installs.take(30).forEach { install ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(oneDevsColors.live.solid),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = install.model ?: stringResource(R.string.gl_unknown_phone),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    install.sdk?.let {
                        Text(
                            text = stringResource(R.string.gl_android, androidName(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                epochOf(install.at)?.let { at ->
                    Text(
                        text = stringResource(R.string.gl_ago, short((now - at).coerceAtLeast(60_000L))),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** Choosing the app and paying. */
@Composable
private fun StartRun(
    hasRuns: Boolean,
    apps: List<Pair<String, String>>,
    price: String?,
    buying: Boolean,
    onBuy: (String) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = chosen?.takeIf { id -> apps.any { it.first == id } } ?: apps.firstOrNull()?.first
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(
            text = stringResource(if (hasRuns) R.string.gl_another else R.string.gl_how_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!hasRuns) {
            listOf(
                stringResource(R.string.gl_how_1),
                stringResource(R.string.gl_how_2),
                stringResource(R.string.gl_how_3),
                stringResource(R.string.gl_f_guarantee),
            ).forEach { Feature(it, scheme.primary, scheme.onSurface) }
        }
        if (apps.isEmpty()) {
            Text(
                text = stringResource(R.string.gl_no_app),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
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
                        .border(if (on) 2.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant, RoundedCornerShape(14.dp))
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
        }
        ActionButton(
            text = when {
                buying -> stringResource(R.string.plans_opening)
                price != null -> stringResource(R.string.gl_buy, price)
                else -> stringResource(R.string.gl_start)
            },
            enabled = selected != null && !buying,
            filled = true,
            onClick = { selected?.let(onBuy) },
        )
    }
}

@Composable
internal fun PastRun(run: GhostRun) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        RunIcon(run, Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = run.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (run.state == "cancelled") {
                    stringResource(R.string.gl_state_cancelled)
                } else {
                    pluralStringResource(R.plurals.gl_state_done, run.testers, run.testers)
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunIcon(run: GhostRun, modifier: Modifier) {
    val context = LocalContext.current
    var icon by remember(run.listing, run.iconUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(run.listing, run.iconUrl) {
        icon = cachedImage(context, "icon-${run.listing}", run.iconUrl)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(12.dp)))
    } else {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(GhostWash))
    }
}
