package com.devbangs.onedevs.ui.missions

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.missions.Mission
import com.devbangs.onedevs.data.missions.MissionResult
import com.devbangs.onedevs.data.missions.MissionSeat
import com.devbangs.onedevs.data.missions.MissionStage
import com.devbangs.onedevs.data.play.parseOptInLink
import com.devbangs.onedevs.data.play.packageOrNull
import com.devbangs.onedevs.ui.board.openPlayListing
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Opens another member's app where a tester gets it: the Play link they filed
 * when it names a package, otherwise the store page for the package.
 */
internal fun openSeat(context: Context, seat: MissionSeat) {
    val link = seat.playUrl?.let(::parseOptInLink)
    if (link?.packageOrNull() != null) {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, seat.playUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (opened) return
    }
    seat.packageName?.let { openPlayListing(context, it) }
}

/** What a member has to do, as three lines that tick. */
@Composable
internal fun YourMission(mission: Mission) {
    val scheme = MaterialTheme.colorScheme
    val tasks = mission.others.size
    val filling = mission.stage == MissionStage.Recruiting
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(oneDevsColors.card, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.mission_your_mission),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = oneDevsColors.mission.solid,
        )
        Step(stringResource(R.string.mission_step_joined), done = true)
        Step(
            text = stringResource(
                if (filling) R.string.mission_step_preinstall else R.string.mission_step_daily,
                mission.tasksDone,
                tasks,
            ),
            done = tasks > 0 && mission.tasksDone >= tasks,
        )
        if (filling) {
            Step(
                text = stringResource(R.string.mission_step_fill, mission.slots, mission.joined),
                done = false,
            )
        } else {
            Step(
                text = stringResource(R.string.mission_day, mission.day, mission.windowDays),
                done = mission.over,
            )
        }
        if (tasks > 0) {
            LinearProgressIndicator(
                progress = { mission.tasksDone.toFloat() / tasks },
                strokeCap = StrokeCap.Round,
                color = oneDevsColors.mission.solid,
                trackColor = scheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
        }
    }
}

@Composable
private fun Step(text: String, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (done) {
                        Modifier.background(oneDevsColors.live.solid)
                    } else {
                        Modifier.background(oneDevsColors.well)
                    },
                ),
        ) {
            if (done) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = scheme.surface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

/** One other member's app: tap to open it, and it ticks once it counted. */
@Composable
internal fun TaskRow(seat: MissionSeat, done: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(oneDevsColors.card, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SeatIcon(seat, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.mission_task_title, seat.ownerName ?: seat.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = seat.title,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .then(
                    if (done) {
                        Modifier.background(oneDevsColors.live.solid)
                    } else {
                        Modifier.background(oneDevsColors.mission.tint)
                    },
                ),
        ) {
            Icon(
                painter = painterResource(if (done) R.drawable.ic_check else R.drawable.ic_caret_right),
                contentDescription = stringResource(
                    if (done) R.string.mission_task_done else R.string.mission_task_open,
                ),
                tint = if (done) scheme.surface else oneDevsColors.mission.solid,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Every member and how far through the others they are. */
@Composable
internal fun MemberProgress(mission: Mission) {
    val scheme = MaterialTheme.colorScheme
    val of = (mission.joined - 1).coerceAtLeast(0)
    val filling = mission.stage == MissionStage.Recruiting
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(oneDevsColors.card, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.mission_members),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // Members' seats only: an app placed in the mission tests nobody.
        mission.seats.filter { it.seat >= 1 }.forEach { seat ->
            val tested = seat.tested ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeatIcon(seat, Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (seat.mine) {
                            stringResource(R.string.mission_you)
                        } else {
                            seat.ownerName ?: seat.title
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (of == 0) 0f else tested.toFloat() / of },
                        strokeCap = StrokeCap.Round,
                        color = if (of > 0 && tested >= of) oneDevsColors.live.solid else oneDevsColors.mission.solid,
                        trackColor = scheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (filling) R.string.mission_member_filling else R.string.mission_member_running,
                        tested,
                        of,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The way into Mission Command. */
@Composable
internal fun CommandEntry(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = oneDevsColors.community
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.tint)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.solid),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chat_circle_dots),
                contentDescription = null,
                tint = scheme.surface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.mission_command),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.mission_command_sub),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_caret_right),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SeatIcon(seat: MissionSeat, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var icon by remember(seat.listing, seat.iconUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(seat.listing, seat.iconUrl) {
        icon = cachedImage(context, "icon-${seat.listing}", seat.iconUrl)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/**
 * How the mission ended for you: whether you did your part, and what it paid.
 * Someone who fell short is told by how much, not just that they did.
 */
@Composable
internal fun MissionEnding(mission: Mission, result: MissionResult) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (result.didPart) oneDevsColors.live else oneDevsColors.critical
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.tint)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.mission_result_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent.solid,
        )
        if (result.didPart) {
            Text(
                text = stringResource(R.string.mission_result_did_part, mission.daysNeeded, mission.windowDays),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (result.coins > 0) {
                Text(
                    text = pluralStringResource(R.plurals.mission_result_paid, result.coins, result.coins),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent.solid,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.mission_result_missed, result.days, mission.daysNeeded),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        mission.completers?.let {
            Text(
                text = stringResource(R.string.mission_result_completers, it, mission.joined),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Days you have used each other app, against the days that count as your part. */
@Composable
internal fun DaysPerApp(mission: Mission) {
    val scheme = MaterialTheme.colorScheme
    val apps = mission.others.filter { it.seat >= 1 }
    if (apps.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(oneDevsColors.card, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.mission_days_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        apps.sortedBy { it.myDays ?: 0 }.forEach { seat ->
            val days = seat.myDays ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = seat.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.mission_days_value, days, mission.daysNeeded),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (days >= mission.daysNeeded) oneDevsColors.live.solid else scheme.onSurfaceVariant,
                )
            }
        }
    }
}
