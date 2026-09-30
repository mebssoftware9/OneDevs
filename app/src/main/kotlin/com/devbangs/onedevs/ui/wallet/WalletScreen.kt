package com.devbangs.onedevs.ui.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Balance
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.claims.ClaimRecord
import com.devbangs.onedevs.data.claims.ClaimStatus
import com.devbangs.onedevs.data.tests.epochMillis
import com.devbangs.onedevs.data.tests.reasonText
import com.devbangs.onedevs.data.wallet.Wallet
import com.devbangs.onedevs.data.wallet.WalletEntry
import com.devbangs.onedevs.data.wallet.entryLabel
import com.devbangs.onedevs.ui.components.BrandedLoading
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.text.DateFormat
import java.util.Date

/** Refusals older than this have been seen and stop crowding the top of the wallet. */
private const val RECENT_REFUSALS_MS = 7L * 24 * 60 * 60 * 1000

/**
 * DevCoins: what you have, what is on its way, and every movement with the
 * thing it was for.
 *
 * Claims this phone is still confirming are shown as their own line rather
 * than hidden in the total, and refusals stay listed with their reason. A
 * coin that arrives or does not arrive is always accounted for here.
 */
@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val balance by app.account.balance.collectAsState()
    val session by app.account.session.collectAsState()
    val records by app.claims.records.collectAsState()
    val mine = records.filter { it.account == session?.userId }
    val pending = mine.filter { it.status == ClaimStatus.Pending }
    val now = System.currentTimeMillis()
    val refused = mine.filter { it.status == ClaimStatus.Refused && now - it.settledAt < RECENT_REFUSALS_MS }

    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(tick, session?.userId) {
        when (val reply = com.devbangs.onedevs.data.backend.quietly(quiet = wallet != null) { app.wallet.load() }) {
            is Reply.Answer -> {
                wallet = reply.value
                failed = false
            }
            else -> failed = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (opened) tick++ else opened = true
    }

    val shown = (balance as? Balance.Known)?.coins ?: wallet?.balance
    if (wallet == null && !failed) {
        BrandedLoading(modifier)
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item { Hero(shown) }
        item {
            // One height for all three, so a label that wraps in one language
            // does not leave that box taller than its neighbours.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                Stat(stringResource(R.string.wallet_available), wallet?.available, Modifier.weight(1f))
                Stat(stringResource(R.string.wallet_reserved), wallet?.held, Modifier.weight(1f))
                Stat(stringResource(R.string.wallet_pending), pending.sumOf { it.coins }, Modifier.weight(1f))
            }
        }
        if (failed) {
            item { Note(stringResource(R.string.wallet_offline)) }
        }
        if (pending.isNotEmpty() || refused.isNotEmpty()) {
            item { Heading(stringResource(R.string.wallet_claims)) }
            items(pending + refused, key = { it.id }) { ClaimRow(it) }
        }
        val entries = wallet?.entries.orEmpty()
        item { Heading(stringResource(R.string.wallet_history)) }
        if (entries.isEmpty()) {
            item {
                Note(
                    stringResource(
                        if (failed) R.string.wallet_offline else R.string.wallet_empty_body,
                    ),
                )
            }
        } else {
            items(entries) { EntryRow(it) }
        }
    }
}

@Composable
private fun Hero(coins: Int?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_devcoin_hero),
            contentDescription = null,
            modifier = Modifier.size(84.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = coins?.toString() ?: "–",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.wallet_devcoins),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: Int?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
    ) {
        Text(
            text = value?.toString() ?: "–",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun date(millis: Long?): String {
    val locale = LocalConfiguration.current.locales[0]
    return millis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)) }
        .orEmpty()
}

/** A claim this phone made: still confirming, or refused and why. */
@Composable
private fun ClaimRow(claim: ClaimRecord) {
    val waiting = claim.status == ClaimStatus.Pending
    val accent = if (waiting) oneDevsColors.caution else oneDevsColors.critical
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.tint)
            .padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = claim.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (waiting) {
                    stringResource(R.string.wallet_claim_waiting)
                } else {
                    stringResource(reasonText(claim.reason))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (waiting) "+${claim.coins}" else "0",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent.solid,
        )
    }
}

@Composable
private fun EntryRow(entry: WalletEntry) {
    val scheme = MaterialTheme.colorScheme
    val credit = entry.delta > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(entryLabel(entry.reason), entry.title.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = date(epochMillis(entry.at)),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box {
            Text(
                text = if (credit) "+${entry.delta}" else entry.delta.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (credit) oneDevsColors.live.solid else scheme.onSurface,
            )
        }
    }
}
