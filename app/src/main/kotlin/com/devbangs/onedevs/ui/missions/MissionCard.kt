package com.devbangs.onedevs.ui.missions

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R

/**
 * Everything on the card is white at some alpha, and nothing uses the palette's
 * accents. Those are picked for contrast against a white page, so on a dark
 * indigo backing the light-theme ones would all but vanish -- and reaching for
 * the dark-theme ones would mean the card looked different depending on a
 * setting that has nothing to do with it. White is legible on this artwork at
 * 4.98:1 in its worst band, which is the whole reason it can be this simple.
 */
private val Ink = Color.White
private val InkMuted = Color.White.copy(alpha = 0.72f)
private val Wash = Color.White.copy(alpha = 0.16f)
private val SlotEmpty = Color.White.copy(alpha = 0.14f)

/**
 * One mission: a group of developers testing each other's apps for the window.
 *
 * The slot grid is the point of the card. A mission is worth joining when it is
 * nearly full and worth nothing when it is nearly empty, and sixteen tiles say
 * that faster than "14/16" does -- the number is there for people who want it
 * exactly.
 */
@Composable
fun MissionCard(
    mission: Mission,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val joinable = !mission.member && mission.stage == MissionStage.Gathering
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
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
                            MissionStage.Gathering -> R.string.mission_gathering
                            MissionStage.Running -> R.string.mission_running
                            MissionStage.Elapsed -> R.string.mission_elapsed
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
                    text = stringResource(
                        R.string.mission_slots, mission.joined, MissionRules.SLOTS,
                    ),
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
                    stringResource(R.string.mission_day, mission.day, MissionRules.WINDOW_DAYS)
                } else {
                    pluralStringResource(R.plurals.mission_co_testers, mission.coTesters, mission.coTesters)
                },
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted,
            )
            Spacer(Modifier.height(14.dp))
            SlotGrid(mission.joined)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    when {
                        joinable -> R.string.mission_cta_join
                        mission.member && mission.stage == MissionStage.Elapsed ->
                            R.string.mission_cta_report
                        mission.member -> R.string.mission_cta_open
                        else -> R.string.mission_cta_full
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                // A white button with the brand's own blue on it. The palette's
                // blues are chosen against a white page and would sink into
                // this backing; inverting keeps the brand and the contrast.
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(Ink)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * Sixteen tiles, one per slot, filled left to right.
 *
 * Two rows of eight rather than a wrap: the shape has to be the same on every
 * card so that half-full and nearly-full are told apart at a glance, and a
 * FlowRow would reflow with the screen and take that away.
 */
@Composable
private fun SlotGrid(filled: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(MissionRules.SLOTS / 2) { column ->
                    val index = row * (MissionRules.SLOTS / 2) + column
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (index < filled) Wash else SlotEmpty),
                    ) {
                        if (index < filled) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = InkMuted,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
