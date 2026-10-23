package com.devbangs.onedevs.ui.academy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.academy.CapstoneProgress
import com.devbangs.onedevs.data.academy.CapstoneSpec
import com.devbangs.onedevs.data.academy.Competency
import com.devbangs.onedevs.data.academy.Progress
import com.devbangs.onedevs.data.academy.Standing
import com.devbangs.onedevs.ui.plans.PremiumStyle
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.text.DateFormat
import java.util.Date

// The Academy's own pieces. Every surface follows the app's rule: hierarchy
// from fill, never from outlines. Rows and panels are cards on the page, what
// sits inside a card is a well, a selected answer is brandTint, and the one
// hero -- the pathway at the top -- wears the brand sky.

private val Shape = RoundedCornerShape(18.dp)
private val SkyTop = Color(0xFF0A1C8F)
private val SkyBottom = Color(0xFF04115E)
private val SkyGlow = Color(0xFF1048FF)

@Composable
internal fun day(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, LocalConfiguration.current.locales[0]).format(Date(millis))

@Composable
internal fun clock(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, LocalConfiguration.current.locales[0]).format(Date(millis))

/** A card on the page: the container for every section and row. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape)
            .background(oneDevsColors.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 6.dp),
    )
}

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** A full-width button. Filled is the one thing a screen is for; the rest sit on brandTint. */
@Composable
internal fun WideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val fill = when {
        !enabled -> scheme.surfaceContainerHigh
        filled -> scheme.primary
        else -> oneDevsColors.brandTint
    }
    val ink = when {
        !enabled -> scheme.onSurfaceVariant
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

/** Progress as a filled bar on a track of the same hue. */
@Composable
internal fun Bar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** What a learner should do next, on the brand sky. */
@Composable
internal fun PathwayHero(
    title: String,
    summary: String,
    verified: Int,
    total: Int,
    action: String?,
    dueCount: Int,
    onAction: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = SkyGlow, spotColor = SkyGlow)
            .clip(shape)
            .background(Brush.linearGradient(listOf(SkyTop, SkyBottom)))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(SkyGlow.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width, 0f),
                )
            }
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_graduation_cap_fill),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = stringResource(R.string.acad_progress, verified, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }
        }
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.86f))
        Bar(
            fraction = if (total == 0) 0f else verified / total.toFloat(),
            color = Color.White,
            track = Color.White.copy(alpha = 0.18f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (action != null) {
                HeroButton(action, onAction, Modifier.weight(1f))
            } else {
                Text(
                    text = stringResource(R.string.acad_all_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
            }
            if (dueCount > 0) {
                HeroButton(pluralStringResource(R.plurals.acad_reviews_due, dueCount, dueCount), onReview, soft = true)
            }
        }
    }
}

@Composable
private fun HeroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, soft: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (soft) Color.White else SkyBottom,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(if (soft) Color.White.copy(alpha = 0.16f) else Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** The accent a competency's state wears. */
@Composable
internal fun Standing?.accent(open: Boolean): Accent = when {
    !open -> oneDevsColors.mission
    this == Standing.Verified -> oneDevsColors.live
    this == Standing.ReviewDue -> oneDevsColors.caution
    else -> oneDevsColors.testing
}

/** One line saying where a competency stands, in words. */
@Composable
internal fun standingText(progress: Progress?, open: Boolean): String = when {
    !open -> stringResource(R.string.acad_state_locked)
    progress == null || progress.standing == Standing.NotStarted -> stringResource(R.string.acad_state_new)
    progress.standing == Standing.Verified -> stringResource(R.string.acad_state_verified)
    progress.standing == Standing.ReviewDue -> stringResource(R.string.acad_state_due)
    else -> stringResource(
        R.string.acad_state_learning,
        progress.secureFormative + progress.secureSummative,
        progress.formative + progress.summative,
    )
}

/** A competency on the pathway: its number, name, time, and where the learner stands. */
@Composable
internal fun CompetencyRow(number: Int, competency: Competency, progress: Progress?, open: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = progress?.standing.accent(open)
    val done = open && progress?.verified == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shape)
            .background(oneDevsColors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (done || !open) accent.solid else accent.tint),
        ) {
            if (done) {
                Icon(painterResource(R.drawable.ic_check), null, tint = accent.onSolid, modifier = Modifier.size(16.dp))
            } else if (!open) {
                Icon(painterResource(R.drawable.ic_lock), null, tint = accent.onSolid, modifier = Modifier.size(15.dp))
            } else {
                Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent.solid)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(competency.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(
                text = stringResource(R.string.acad_minutes, competency.minutes) + " · " + standingText(progress, open),
                style = MaterialTheme.typography.labelSmall,
                color = if (progress?.standing == Standing.ReviewDue && open) accent.solid else scheme.onSurfaceVariant,
            )
            if (competency.gate != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Icon(painterResource(R.drawable.ic_package), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.acad_gate_badge),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_caret_right),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The capstone: the learner's own closed test, as the server counts it. */
@Composable
internal fun CapstoneCard(spec: CapstoneSpec, capstone: CapstoneProgress?, onLaunches: () -> Unit) {
    val live = oneDevsColors.live
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (capstone?.met == true) live.solid else live.tint),
            ) {
                Icon(
                    painterResource(if (capstone?.met == true) R.drawable.ic_check else R.drawable.ic_rocket_launch),
                    null,
                    tint = if (capstone?.met == true) live.onSolid else live.solid,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(spec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Body(spec.outcome, muted = true)
        val title = capstone?.title
        if (capstone == null || capstone.listing == null || title == null) {
            Well { Body(stringResource(R.string.acad_capstone_none)) }
            WideButton(stringResource(R.string.acad_capstone_open), onLaunches, filled = false)
        } else {
            Well {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Meter(
                    stringResource(R.string.acad_capstone_testers, capstone.testers, capstone.neededTesters),
                    capstone.testers / capstone.neededTesters.toFloat(),
                )
                Meter(
                    stringResource(R.string.acad_capstone_days, capstone.days, capstone.neededDays),
                    capstone.days / capstone.neededDays.toFloat(),
                )
            }
            Body(
                stringResource(if (capstone.met) R.string.acad_capstone_met else R.string.acad_capstone_counting, title),
                muted = !capstone.met,
            )
        }
    }
}

@Composable
private fun Meter(label: String, fraction: Float) {
    val live = oneDevsColors.live
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Bar(fraction, live.solid, live.tint)
    }
}

/** A field set into a card. */
@Composable
internal fun Well(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(oneDevsColors.well)
            .padding(12.dp),
    ) { content() }
}

/** Premium's own colours: the pathway beyond the free lessons. */
@Composable
internal fun UpgradeCard(onPlans: () -> Unit, modifier: Modifier = Modifier) {
    val style = PremiumStyle
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(style.fill)
            .clickable(role = Role.Button, onClick = onPlans)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_sparkle_fill), null, tint = style.mark, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.acad_upgrade_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = style.ink,
            )
        }
        Text(stringResource(R.string.acad_upgrade_body), style = MaterialTheme.typography.bodyMedium, color = style.muted)
        Text(
            text = stringResource(R.string.acad_upgrade_action),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = style.onAccent,
            modifier = Modifier
                .clip(CircleShape)
                .background(style.accent)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/**
 * Code, a line at a time, in a well. With [onLine] each line is a choice;
 * [picked] is the learner's, and once [answer] is known it is marked too.
 */
@Composable
internal fun CodeLines(
    code: List<String>,
    modifier: Modifier = Modifier,
    onLine: ((Int) -> Unit)? = null,
    picked: Int? = null,
    answer: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(oneDevsColors.well)
            .padding(vertical = 8.dp),
    ) {
        // Lines keep their shape and scroll sideways rather than wrap, and
        // every line is as wide as the well or the longest line, so a marked
        // line is marked edge to edge.
        BoxWithConstraints {
            val well = maxWidth
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(IntrinsicSize.Max)
                    .widthIn(min = well),
            ) {
                code.forEachIndexed { i, line ->
                val fill = when {
                    answer != null && i == answer -> oneDevsColors.live.tint
                    answer != null && i == picked -> oneDevsColors.critical.tint
                    i == picked -> oneDevsColors.brandTint
                    else -> Color.Transparent
                }
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
                    color = scheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(fill)
                        .then(
                            if (onLine != null && line.isNotBlank()) {
                                Modifier.clickable(role = Role.RadioButton) { onLine(i) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = if (onLine != null) 6.dp else 1.dp),
                )
                }
            }
        }
    }
}
