package com.devbangs.onedevs.ui.board

import android.text.format.Formatter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.ui.components.rememberListingIcon
import com.devbangs.onedevs.ui.theme.oneDevsColors

/** Testers Google asks for before a closed test can apply for production. */
private const val TESTERS_NEEDED = 12

/** An app counts as new on the Board for its first two days. */
private const val NEW_FOR_MS = 48L * 60 * 60 * 1000

// The tab is the brand's blue so the gold DevCoin stands out on it. Gold on
// gold lost the coin, and amber already means "still needs testers" here.
private val TabTop = Color(0xFF2F6BFF)
private val TabBottom = Color(0xFF0B3BD1)

// The Spotlight card: a royal night sky with gold on it. It has to read as
// the most valuable thing on the Board at a glance, because it is -- it pays
// what OneDevs pays, not what a developer can spare.
private val RoyalTop = Color(0xFF1B0F4F)
private val RoyalMid = Color(0xFF34188A)
private val RoyalBottom = Color(0xFF0A2A8E)
private val GoldLight = Color(0xFFFFE08A)
private val GoldDeep = Color(0xFFE9A824)
private val GoldInk = Color(0xFF3B2500)
private val MintOnDark = Color(0xFF7CF2C4)
private val OnRoyal = Color(0xFFE9E6FF)

/**
 * One app waiting for testers, on either board.
 *
 * Read top to bottom the way a tester decides: what it pays (the coin tab in
 * the corner), what it is (icon and name), how close it is to Google's twelve
 * testers, whether its developer tests other people's apps too, and what kind
 * of app it is and how big. A spotlighted app is tinted and marked, and pays
 * what OneDevs pays for it.
 *
 * The icon comes from the listing, not the device: a tester browsing the
 * Board has not installed the app yet. No star rating: there is no rating
 * system, and a star with a number is the most believable thing on a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoardRow(
    listing: Listing,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (listing.spotlight) {
        SpotlightRow(listing, onClick, modifier)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val icon = rememberListingIcon(listing)
    val shape = RoundedCornerShape(24.dp)
    val isNew = listing.createdAt > 0L && System.currentTimeMillis() - listing.createdAt < NEW_FOR_MS

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(oneDevsColors.card)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        ) {
            AppIcon(listing.title, icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = listing.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Clear of the coin tab in the corner.
                    modifier = Modifier.padding(end = 72.dp),
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listing.testers?.let { Progress(it) }
                    listing.ownerTested?.takeIf { it > 0 }?.let { GivesBack(it) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOfNotNull(
                            listing.category.ifBlank { null },
                            listing.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNew) {
                        Pill(
                            text = stringResource(R.string.board_card_new),
                            fill = oneDevsColors.caution.solid,
                            ink = oneDevsColors.caution.onSolid,
                        )
                    }
                }
            }
        }
        CoinTab(
            coins = listing.reward,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * A spotlighted app. Same facts as any row -- what it pays, how close it is to
 * Google's twelve, what it is -- set on a royal gradient with gold, a soft
 * light passing over it now and then, and a button that says what to do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotlightRow(listing: Listing, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val icon = rememberListingIcon(listing)
    val shape = RoundedCornerShape(26.dp)
    val sweep by rememberInfiniteTransition(label = "spotlight").animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, delayMillis = 2600, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = RoyalMid, spotColor = RoyalMid)
            .clip(shape)
            .background(Brush.linearGradient(listOf(RoyalTop, RoyalMid, RoyalBottom)))
            .drawWithContent {
                drawContent()
                // A gold glow behind the corner the reward sits in.
                drawRect(
                    Brush.radialGradient(
                        listOf(GoldLight.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * 0.7f,
                    ),
                )
                // The light passing over: a narrow diagonal band.
                val x = size.width * sweep
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.14f),
                        1f to Color.Transparent,
                        start = Offset(x - size.width * 0.25f, 0f),
                        end = Offset(x + size.width * 0.05f, size.height),
                    ),
                )
            }
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_crown_fill),
                    contentDescription = null,
                    tint = GoldLight,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.board_spotlight).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
                    fontWeight = FontWeight.Bold,
                    color = GoldLight,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconShape = RoundedCornerShape(20.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(14.dp, iconShape, ambientColor = GoldDeep, spotColor = GoldDeep)
                        .clip(iconShape)
                        .background(RoyalTop),
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp),
                        )
                    } else {
                        Text(
                            text = listing.title.trim().take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = GoldLight,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listing.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listing.testers?.let { Progress(it, onDark = true) }
                        listing.ownerTested?.takeIf { it > 0 }?.let { GivesBack(it, onDark = true) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOfNotNull(
                            listing.category.ifBlank { null },
                            listing.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnRoyal.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(GoldLight, GoldDeep)))
                    .padding(vertical = 11.dp),
            ) {
                Text(
                    text = stringResource(R.string.board_spotlight_cta, listing.reward),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GoldInk,
                )
                Spacer(Modifier.width(6.dp))
                Image(
                    painter = painterResource(R.drawable.ic_devcoin),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(CircleShape),
                )
            }
        }
        CoinTab(coins = listing.reward, modifier = Modifier.align(Alignment.TopEnd), gold = true)
    }
}

/** The app's own mark, large and lifted; its first letter until one is set. */
@Composable
private fun AppIcon(title: String, icon: androidx.compose.ui.graphics.ImageBitmap?) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(64.dp)
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(oneDevsColors.brandTint),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        } else {
            // Never pretends to be the app's own mark.
            Text(
                text = title.trim().take(1).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** How many have tested it, and how many more until Google's twelve. */
@Composable
private fun Progress(testers: Int, onDark: Boolean = false) {
    val reached = testers >= TESTERS_NEEDED
    val accent = if (reached) oneDevsColors.live else oneDevsColors.caution
    val ink = when {
        !onDark -> accent.solid
        reached -> MintOnDark
        else -> GoldLight
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_users),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (reached) {
                pluralStringResource(R.plurals.board_card_reached, testers, testers)
            } else {
                pluralStringResource(R.plurals.board_card_progress, testers, testers, TESTERS_NEEDED - testers)
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
    }
}

/** How many apps its developer has tested for others. */
@Composable
private fun GivesBack(apps: Int, onDark: Boolean = false) {
    val accent = oneDevsColors.live
    val ink = if (onDark) MintOnDark else accent.solid
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_seal_check),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.board_card_gives, apps, apps),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color, icon: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(10.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
        )
    }
}

/** What a test pays, as a blue tab folded over the card's corner, the coin in gold. */
@Composable
private fun CoinTab(coins: Int, modifier: Modifier = Modifier, gold: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 24.dp, bottomStart = 18.dp))
            .background(Brush.verticalGradient(if (gold) listOf(GoldLight, GoldDeep) else listOf(TabTop, TabBottom)))
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.board_card_earn, coins),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (gold) GoldInk else Color.White,
        )
        Image(
            painter = painterResource(R.drawable.ic_devcoin),
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape),
        )
    }
}
