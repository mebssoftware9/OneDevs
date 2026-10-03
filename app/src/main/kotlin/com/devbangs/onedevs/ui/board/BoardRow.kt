package com.devbangs.onedevs.ui.board

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.ui.components.rememberListingIcon
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * One app waiting for testers, on either board.
 *
 * One component for both, because a listing is a listing: the only thing that
 * differs between the boards is whether the app is already public, and that is
 * a property of the listing rather than of the row.
 *
 * The icon comes from the listing, not from the device. Asking PackageManager
 * was wrong twice over -- a tester browsing the board has not installed the app
 * yet, and Android 11 hides packages the manifest never declared, so it could
 * only ever have resolved for three apps.
 *
 * No rating. There is no rating system, and a star with a number beside it is
 * the most believable thing on a row.
 *
 * A spotlighted app -- one day in four of its owner's testing cycle -- is
 * tinted and marked Spotlight, so the whole community can see it, and its
 * reward is the one OneDevs pays for it.
 */
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (listing.spotlight) spot.tint else oneDevsColors.card)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(oneDevsColors.brandTint),
        ) {
            val art = icon
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)),
                )
            } else {
                // Never pretends to be the app's own mark.
                Text(
                    text = listing.title.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = listing.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (listing.spotlight) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(CircleShape)
                        .background(spot.solid)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sparkle_fill),
                        contentDescription = null,
                        tint = spot.onSolid,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = stringResource(R.string.board_spotlight),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = spot.onSolid,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    listing.category.ifBlank { null },
                    listing.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                ).joinToString(" \u00b7 "),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.width(8.dp))
        // What this test pays, from the listing itself. The board only shows
        // apps whose owner can still afford it, so the number is a promise the
        // platform can keep.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(oneDevsColors.brandTint)
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_coins),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.board_reward, listing.reward, listing.reward),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                maxLines = 1,
            )
        }
    }
}
