package com.devbangs.onedevs.ui.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Balance
import com.devbangs.onedevs.data.listings.Channel
import com.devbangs.onedevs.data.missions.JoinResult
import com.devbangs.onedevs.data.missions.Mission
import com.devbangs.onedevs.data.missions.MissionRules
import com.devbangs.onedevs.data.missions.MissionStage
import com.devbangs.onedevs.data.tests.REQUIRED_SECONDS
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.data.usage.foregroundSeconds
import com.devbangs.onedevs.data.usage.hasUsageAccess
import com.devbangs.onedevs.data.usage.usageAccessSettings
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.EmptyState
import com.devbangs.onedevs.ui.components.FilterPills
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.launch

/**
 * Missions: the one that is recruiting, and the ones you are in.
 *
 * One mission recruits at a time, so Available is a single card rather than a
 * list. Everyone joining fills the same group, which is how a group actually
 * fills -- sixteen half-empty ones would each wait on the others.
 */
@Composable
fun MissionsScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var mine by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as OneDevsApplication

    // null is "not read yet", which has to look different from nothing.
    var current by remember { mutableStateOf<Mission?>(null) }
    var joined by remember { mutableStateOf<List<Mission>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        // A refresh behind missions already on screen is background work.
        com.devbangs.onedevs.data.backend.quietly(quiet = loaded) {
            current = app.missions.current()
            joined = app.missions.mine().orEmpty()
        }
        loaded = true
    }
    // Re-read on the way back in: joining happens on the mission's own page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (opened) tick++ else opened = true
    }

    val shown: List<Mission> = if (mine) joined.orEmpty() else listOfNotNull(current)

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.missions_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )
        FilterPills(
            labels = listOf(
                stringResource(R.string.missions_available),
                stringResource(R.string.missions_mine),
            ),
            selected = if (mine) 1 else 0,
            onSelect = { mine = it == 1 },
            fillWidth = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
        when {
            !loaded -> Box(modifier = Modifier.weight(1f)) { BrandedLoading() }

            shown.isEmpty() -> Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = stringResource(
                        if (mine) R.string.missions_empty_mine_title else R.string.missions_empty_title,
                    ),
                    body = stringResource(
                        if (mine) R.string.missions_empty_mine_body else R.string.missions_empty_body,
                    ),
                ) { DevBotMark() }
            }

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(shown, key = { it.id }) { mission ->
                    MissionCard(mission = mission, onClick = { onOpen(mission.id) })
                }
                // At the foot of the list rather than the head of the screen.
                // OneDevs counting days and Google Play deciding tests is the
                // one thing this app must not blur.
                item {
                    Text(
                        text = stringResource(R.string.missions_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One mission's own page: who is in it, the rules, and the way in.
 *
 * The fee is on the button, not behind it. Someone deciding whether to spend
 * a hundred DevCoins should see the number on the thing they press.
 */
@Composable
fun MissionDetailsScreen(missionId: String, onCommand: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()

    var mission by remember { mutableStateOf<Mission?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(missionId, tick) {
        // A re-read behind a mission already on screen is background work.
        val fresh = com.devbangs.onedevs.data.backend.quietly(quiet = loaded) { app.missions.find(missionId) }
        if (fresh != null || !loaded) mission = fresh
        loaded = true
    }

    // A task in progress: which app was opened, and when. Measured on the way
    // back in from the usage record, the same evidence a paid test uses.
    var taskListing by rememberSaveable { mutableStateOf<String?>(null) }
    var taskStarted by rememberSaveable { mutableLongStateOf(0L) }
    var wentAway by remember { mutableStateOf(false) }
    var taskNote by remember { mutableStateOf<String?>(null) }
    var needsAccess by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (taskListing != null) wentAway = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (needsAccess && hasUsageAccess(context)) needsAccess = false
        val listing = taskListing
        val seat = mission?.seats?.firstOrNull { it.listing == listing }
        if (listing != null && wentAway && seat?.packageName != null && hasUsageAccess(context)) {
            wentAway = false
            val started = taskStarted
            scope.launch {
                val seconds = foregroundSeconds(context, seat.packageName, started)
                if (seconds < REQUIRED_SECONDS) {
                    taskNote = context.getString(R.string.mission_task_short, seat.title, seconds, REQUIRED_SECONDS)
                    return@launch
                }
                val ack = app.missions.checkIn(missionId, listing, seconds, deviceId(context))
                if (ack?.ok == true) {
                    taskListing = null
                    taskNote = context.getString(R.string.mission_task_saved, seat.title)
                    tick++
                } else {
                    taskNote = context.getString(R.string.mission_task_failed)
                }
            }
        } else if (opened) {
            tick++
        } else {
            opened = true
        }
    }

    val listings by app.listings.listings.collectAsState(initial = emptyList())
    val testing = listings.filter { it.channel == Channel.Testing }
    val balance by app.account.balance.collectAsState()
    val coins = (balance as? Balance.Known)?.coins

    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<JoinResult?>(null) }
    var failed by remember { mutableStateOf(false) }

    val current = mission
    if (!loaded) {
        BrandedLoading(modifier)
        return
    }
    if (current == null) {
        EmptyState(
            title = stringResource(R.string.mission_not_found),
            body = stringResource(R.string.missions_note),
            modifier = modifier,
        ) { DevBotMark() }
        return
    }

    // The first app is chosen for you. With two or more and nothing picked,
    // the button used to sit grey with no word as to why.
    val selected = chosen?.takeIf { id -> testing.any { it.id == id } } ?: testing.firstOrNull()?.id
    val fee = current.entryFee
    // An unknown balance does not block: the server checks what is available
    // and says so if it is not enough.
    val shortOfCoins = coins != null && coins < fee
    val canJoin = !current.member && current.stage == MissionStage.Recruiting &&
        selected != null && !shortOfCoins && !joining

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        MissionCard(mission = current, onClick = {}, showAction = false)

        if (current.member) {
            Banner(stringResource(R.string.mission_member), good = true)
        }
        outcome?.let { result ->
            if (!result.joined) Banner(stringResource(refusal(result.reason), fee), good = false)
        }
        if (failed) Banner(stringResource(R.string.mission_err_generic), good = false)

        if (current.member) {
            YourMission(current)

            if (current.others.isNotEmpty() && current.stage != MissionStage.Elapsed) {
                Section(stringResource(R.string.mission_tasks, current.others.size))
                Text(
                    text = stringResource(R.string.mission_task_hint, REQUIRED_SECONDS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsAccess) {
                    Banner(stringResource(R.string.mission_usage_needed), good = false)
                    Text(
                        text = stringResource(R.string.mission_usage_grant),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { context.startActivity(usageAccessSettings()) }
                            .padding(vertical = 14.dp),
                    )
                }
                taskNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Undone first: the list is a to-do list.
                    current.others.sortedBy { current.done(it) }.forEach { seat ->
                        TaskRow(seat = seat, done = current.done(seat)) {
                            if (!hasUsageAccess(context)) {
                                needsAccess = true
                                return@TaskRow
                            }
                            taskListing = seat.listing
                            taskStarted = System.currentTimeMillis()
                            taskNote = null
                            openSeat(context, seat)
                        }
                    }
                }
            }

            MemberProgress(current)
            CommandEntry(onClick = onCommand)
        }

        Rules(current)

        if (!current.member && current.stage == MissionStage.Recruiting) {
            Section(stringResource(R.string.mission_your_app))
            if (testing.isEmpty()) {
                Text(
                    text = stringResource(R.string.mission_no_app),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    testing.forEach { listing ->
                        Choice(
                            title = listing.title,
                            detail = listing.packageName,
                            selected = listing.id == selected,
                            onClick = { chosen = listing.id },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.mission_balance, coins?.toString() ?: "–"),
                style = MaterialTheme.typography.bodySmall,
                color = if (coins != null && coins < fee) {
                    oneDevsColors.critical.solid
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(R.string.mission_join_for, fee),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (canJoin) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(
                        if (canJoin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable(enabled = canJoin) {
                        val listing = selected ?: return@clickable
                        joining = true
                        failed = false
                        outcome = null
                        scope.launch {
                            val result = app.missions.join(current.id, listing, deviceId(context))
                            joining = false
                            if (result == null) {
                                failed = true
                            } else {
                                outcome = result
                                if (result.joined) {
                                    app.account.refreshBalance()
                                    tick++
                                }
                            }
                        }
                    }
                    .padding(vertical = 14.dp),
            )
            // A grey button always says why.
            val why = when {
                testing.isEmpty() -> null // mission_no_app is already shown above
                shortOfCoins -> stringResource(R.string.mission_join_need, fee, coins ?: 0)
                testing.size > 1 -> stringResource(R.string.mission_join_pick)
                else -> null
            }
            why?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shortOfCoins) oneDevsColors.critical.solid else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = stringResource(R.string.missions_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The sentence for a refusal the server gave. */
private fun refusal(reason: String?): Int = when (reason) {
    "broke" -> R.string.mission_err_broke
    "busy" -> R.string.mission_err_busy
    "full" -> R.string.mission_err_full
    "device" -> R.string.mission_err_device
    else -> R.string.mission_err_generic
}

@Composable
private fun Section(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The rules, with the numbers the server enforces. */
@Composable
private fun Rules(mission: Mission) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Section(stringResource(R.string.mission_rules_title))
        listOf(
            stringResource(R.string.mission_rule_fee, mission.entryFee),
            stringResource(R.string.mission_rule_slots, mission.slots),
            stringResource(R.string.mission_rule_window, mission.windowDays),
            stringResource(R.string.mission_rule_test),
            stringResource(
                R.string.mission_rule_standing,
                "${(MissionRules.DAILY_THRESHOLD * 100).toInt()}%",
                MissionRules.GRACE_DAYS,
            ),
            stringResource(R.string.mission_rule_one),
        ).forEach { rule ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

/** One of your testing apps, to choose which one takes the seat. */
@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Banner(text: String, good: Boolean) {
    val accent = if (good) oneDevsColors.live else oneDevsColors.critical
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = accent.solid,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.tint)
            .padding(14.dp),
    )
}
