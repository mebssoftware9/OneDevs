package com.devbangs.onedevs.ui.plans

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.plans.Tier
import com.devbangs.onedevs.ui.theme.oneDevsColors

/**
 * Which app the Lab is working on, and the way to another one.
 *
 * On a free plan "Change app" still opens the picker: the answer to a second
 * app is the upgrade sheet, which says why, not a button that does nothing.
 */
@Composable
fun LabAppCard(current: String?, tier: Tier, onChange: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = oneDevsColors.testing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.tint)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.solid),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_code),
                contentDescription = null,
                tint = scheme.surface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = current ?: stringResource(R.string.lab_app_none),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    when (tier) {
                        Tier.Community -> R.string.lab_app_free
                        Tier.Premium -> R.string.lab_app_premium
                        Tier.Pro -> R.string.lab_app_pro
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = accent.solid,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (current == null) R.string.lab_app_choose else R.string.lab_app_change),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent.solid,
            modifier = Modifier
                .clip(CircleShape)
                .background(oneDevsColors.card)
                .clickable(onClick = onChange)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The foot of a free Lab: one line, one button, no alarm. */
@Composable
fun UpgradeBanner(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.lab_upgrade_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.lab_upgrade_body),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.lab_upgrade_action),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .background(scheme.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
