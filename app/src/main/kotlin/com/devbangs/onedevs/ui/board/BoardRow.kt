package com.devbangs.onedevs.ui.board

import android.text.format.Formatter
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
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val icon = rememberListingIcon(listing)
    val spot = oneDevsColors.mission
    val shape = RoundedCornerShape(24.dp)
    val isNew = listing.createdAt > 0L && System.currentTimeMillis() - listing.createdAt < NEW_FOR_MS

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (listing.spotlight) spot.tint else oneDevsColors.card)
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
                    when {
                        listing.spotlight -> Pill(
                            text = stringResource(R.string.board_spotlight),
                            fill = spot.solid,
                            ink = spot.onSolid,
                            icon = R.drawable.ic_sparkle_fill,
                        )
                        isNew -> Pill(
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
private fun Progress(testers: Int) {
    val reached = testers >= TESTERS_NEEDED
    val accent = if (reached) oneDevsColors.live else oneDevsColors.caution
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_users),
            contentDescription = null,
            tint = accent.solid,
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
            color = accent.solid,
        )
    }
}

/** How many apps its developer has tested for others. */
@Composable
private fun GivesBack(apps: Int) {
    val accent = oneDevsColors.live
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_seal_check),
            contentDescription = null,
            tint = accent.solid,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.board_card_gives, apps, apps),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent.solid,
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
private fun CoinTab(coins: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(topEnd = 24.dp, bottomStart = 18.dp))
            .background(Brush.verticalGradient(listOf(TabTop, TabBottom)))
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.board_card_earn, coins),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
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
