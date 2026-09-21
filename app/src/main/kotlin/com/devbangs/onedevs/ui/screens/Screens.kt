package com.devbangs.onedevs.ui.screens

// Thin destinations for now: each moves to its own file once it carries real
// state. Nothing here fabricates data — screens show what is actually known,
// which before the data layer exists is nothing.

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.EmptyState
import com.devbangs.onedevs.ui.components.IconBadge

@Composable
fun BoardScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.board_empty_title),
    body = stringResource(R.string.board_empty_body),
    modifier = modifier,
) { IconBadge(painterResource(R.drawable.ic_squares_four)) }

// Missions is DevBot's screen: the day counters he chases are the thing he
// interrupts you about, so he is who greets you when there is nothing running.
@Composable
fun MissionsScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.missions_empty_title),
    body = stringResource(R.string.missions_empty_body),
    modifier = modifier,
) { DevBotMark() }

@Composable
fun LaunchesScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.launches_empty_title),
    body = stringResource(R.string.launches_empty_body),
    modifier = modifier,
) { IconBadge(painterResource(R.drawable.ic_rocket_launch)) }

@Composable
fun LabScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.lab_empty_title),
    body = stringResource(R.string.lab_empty_body),
    modifier = modifier,
) { IconBadge(painterResource(R.drawable.ic_flask)) }

@Composable
fun BadgeScreen(modifier: Modifier = Modifier) = EmptyState(
    title = stringResource(R.string.badge_empty_title),
    body = stringResource(R.string.badge_empty_body),
    modifier = modifier,
) { IconBadge(painterResource(R.drawable.ic_medal)) }

@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_devcoin_hero),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = stringResource(R.string.wallet_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.wallet_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}
