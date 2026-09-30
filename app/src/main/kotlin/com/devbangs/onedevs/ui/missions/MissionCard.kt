package com.devbangs.onedevs.ui.missions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.missions.Mission
import com.devbangs.onedevs.data.missions.MissionSeat
import com.devbangs.onedevs.data.missions.MissionStage

/**
 * Everything on the card is white at some alpha, and nothing uses the palette's
 * accents. Those are picked for contrast against a white page, so on a dark
 * indigo backing the light-theme ones would all but vanish. White is legible
 * on this artwork at 4.98:1 in its worst band, which is why it can be this
 * simple.
 */
private val Ink = Color.White
private val InkMuted = Color.White.copy(alpha = 0.72f)
private val Wash = Color.White.copy(alpha = 0.16f)
private val SlotEmpty = Color.White.copy(alpha = 0.10f)
private val SlotEdge = Color.White.copy(alpha = 0.18f)
private val Mine = Color.White.copy(alpha = 0.9f)

/**
 * One mission: sixteen developers testing each other's apps for the window.
 *
 * The slots are the point of the card. Each taken seat shows the app sitting
 * in it, so a mission reads as the apps you would be testing, not a count --
 * and a nearly full one looks nearly full before anyone reads "14/16".
 *
 * [showAction] is off on the mission's own page, where joining has a button
 * of its own with the fee on it.
 */
@Composable
fun MissionCard(
    mission: Mission,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAction: Boolean = true,
) {
    val joinable = !mission.member && mission.stage == MissionStage.Recruiting
    val light = rememberRim()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .rim { light.value },
    ) {
        Image(
            painter = painterResource(R.drawable.mission_card),
            contentDescription = null,
            // Cropped from the bottom: the lit ridge is the part of the artwork
            // that reads at this size, and the chevrons above it are texture.
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.matchParentSize(),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        when (mission.stage) {
                            MissionStage.Recruiting -> R.string.mission_recruiting
                            MissionStage.Running -> R.string.mission_running
                            MissionStage.Elapsed -> R.string.mission_elapsed
                            MissionStage.Completed -> R.string.mission_completed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Wash)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.mission_slots, mission.joined, mission.slots),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = mission.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                text = if (mission.stage == MissionStage.Running) {
                    stringResource(R.string.mission_day, mission.day, mission.windowDays)
                } else {
                    pluralStringResource(R.plurals.mission_co_testers, mission.joined, mission.joined)
                },
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted,
            )
            Spacer(Modifier.height(14.dp))
            SlotGrid(mission)
            if (showAction) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        when {
                            joinable -> R.string.mission_cta_join
                            mission.member && mission.over ->
                                R.string.mission_cta_report
                            mission.member -> R.string.mission_cta_open
                            else -> R.string.mission_cta_full
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    // A white button with the brand's own blue on it. The
                    // palette's blues sink into this backing; inverting keeps
                    // the brand and the contrast.
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(Ink)
                        .clickable(onClick = onClick)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * The angle of the rim light, turning once every few seconds.
 *
 * Continuous and linear on purpose. The first version was a band that
 * appeared, crossed and vanished, and the appearing is what read as a flash;
 * a light that never starts or stops has nothing to jar.
 */
@Composable
private fun rememberRim(): State<Float> =
    rememberInfiniteTransition(label = "rim").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 7000, easing = LinearEasing), RepeatMode.Restart),
        label = "rim",
    )

/** Cool white with a hint of the artwork's lilac, so the light belongs to the card. */
private val RimLight = Color(0xFFE4E8FF)

/**
 * A crisp edge of light travelling round the card, with a soft glow inside it.
 *
 * Drawn only on the border -- a gradient spun behind a ring-shaped clip -- so
 * it lights the card's edge rather than washing over the artwork and text.
 * [angle] is read in the draw phase: the card redraws every frame without
 * recomposing.
 */
private fun Modifier.rim(angle: () -> Float): Modifier = drawWithCache {
    val corner = 22.dp.toPx()
    val edge = 1.5.dp.toPx()
    // Three widening rings at falling strength: a glow that fades inward
    // instead of a flat stripe.
    val glows = listOf(3.dp.toPx() to 0.12f, 6.dp.toPx() to 0.06f, 10.dp.toPx() to 0.03f)
    fun ring(width: Float) = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner)))
        addRoundRect(
            RoundRect(
                Rect(Offset(width, width), Size(size.width - width * 2, size.height - width * 2)),
                CornerRadius((corner - width).coerceAtLeast(0f)),
            ),
        )
    }
    val line = ring(edge)
    fun sweep(peak: Float) = Brush.sweepGradient(
        0f to Color.Transparent,
        0.36f to Color.Transparent,
        0.47f to RimLight.copy(alpha = peak * 0.5f),
        0.5f to RimLight.copy(alpha = peak),
        0.53f to RimLight.copy(alpha = peak * 0.5f),
        0.64f to Color.Transparent,
        1f to Color.Transparent,
        center = size.center,
    )
    val bright = sweep(0.95f)
    val halos = glows.map { (width, peak) -> ring(width) to sweep(peak) }
    // Big enough that the rotated gradient always covers the card's corners.
    val reach = size.maxDimension
    val cover = Rect(size.center - Offset(reach, reach), Size(reach * 2, reach * 2))
    onDrawWithContent {
        drawContent()
        val degrees = angle()
        halos.forEach { (ring, brush) ->
            clipPath(ring) {
                rotate(degrees, size.center) { drawRect(brush, cover.topLeft, cover.size) }
            }
        }
        clipPath(line) {
            rotate(degrees, size.center) { drawRect(bright, cover.topLeft, cover.size) }
        }
    }
}

/**
 * Sixteen slots, two rows of eight, filled in seat order.
 *
 * The shape is the same on every card so half-full and nearly-full are told
 * apart at a glance; a flowing layout would reflow with the screen and take
 * that away. Each slot is a square share of the width, so the grid fits any
 * phone without a fixed size that overflows a small one.
 */
@Composable
private fun SlotGrid(mission: Mission) {
    val perRow = (mission.slots + 1) / 2
    val bySeat = mission.seats.associateBy { it.seat }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(perRow) { column ->
                    val seat = bySeat[row * perRow + column + 1]
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (seat != null) Wash else SlotEmpty)
                            .border(
                                width = if (seat?.mine == true) 1.5.dp else 1.dp,
                                color = if (seat?.mine == true) Mine else SlotEdge,
                                shape = RoundedCornerShape(8.dp),
                            ),
                    ) {
                        if (seat != null) SeatIcon(seat)
                    }
                }
            }
        }
    }
}

/** The app in a seat: its icon, or its initial while the icon loads. */
@Composable
private fun SeatIcon(seat: MissionSeat) {
    val context = LocalContext.current
    var icon by remember(seat.listing, seat.iconUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(seat.listing, seat.iconUrl) {
        icon = cachedImage(context, "icon-${seat.listing}", seat.iconUrl)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = seat.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(2.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Text(
            text = seat.title.take(1).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = InkMuted,
        )
    }
}
