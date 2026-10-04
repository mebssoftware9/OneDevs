package com.devbangs.onedevs.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.oneDevsColors

/** The Google Group whose members are OneDevs' closed testers on Play. */
const val TESTERS_GROUP = "https://groups.google.com/g/onedevs-testers"

/**
 * Joining the testers' Google Group, which is what lets an account install
 * the OneDevs test from Google Play.
 *
 * A tap explains what joining does and what comes after, then Understood
 * opens the group, where Google asks the person to sign in and join.
 */
@Composable
fun JoinGroupCard(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    var explaining by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(oneDevsColors.card)
            .clickable { explaining = true }
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(oneDevsColors.brandTint),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_users_three),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.group_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.group_caption),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.primary),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_square_out),
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_users_three),
                    contentDescription = null,
                    tint = scheme.primary,
                )
            },
            title = { Text(stringResource(R.string.group_dialog_title)) },
            text = { Text(stringResource(R.string.group_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = false
                        uri.openUri(TESTERS_GROUP)
                    },
                ) {
                    Text(stringResource(R.string.group_understood), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { explaining = false }) {
                    Text(stringResource(R.string.group_not_now))
                }
            },
            shape = RoundedCornerShape(24.dp),
        )
    }
}
