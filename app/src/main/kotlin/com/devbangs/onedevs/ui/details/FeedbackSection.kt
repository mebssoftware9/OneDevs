package com.devbangs.onedevs.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.data.feedback.FeedbackReport
import com.devbangs.onedevs.data.feedback.FeedbackRules
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.launch

/**
 * Bugs and suggestions about one app.
 *
 * The developer sees every report and marks each one; a tester sees the form
 * and the reports they sent. Whether someone may report at all -- they must
 * have used the app -- is the server's call, and its answer is shown as is.
 */
@Composable
internal fun FeedbackSection(listingId: String, mine: Boolean, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    var reports by remember(listingId) { mutableStateOf<List<FeedbackReport>?>(null) }
    var tick by remember(listingId) { mutableIntStateOf(0) }
    LaunchedEffect(listingId, tick) {
        quietly { app.feedback.forListing(listingId) }?.let { reports = it }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.fb_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!mine) {
            ReportForm(
                onSend = { bug, body, done ->
                    scope.launch {
                        val ack = app.feedback.submit(listingId, bug, body)
                        done(
                            when {
                                ack == null -> R.string.fb_err_generic
                                ack.ok -> R.string.fb_sent
                                ack.reason == "not_tested" -> R.string.fb_err_not_tested
                                ack.reason == "too_short" -> R.string.fb_err_short
                                ack.reason == "too_long" -> R.string.fb_err_long
                                ack.reason == "slow_down" -> R.string.fb_err_slow
                                else -> R.string.fb_err_generic
                            },
                            ack?.ok == true,
                        )
                        if (ack?.ok == true) tick++
                    }
                },
            )
        }
        val list = reports.orEmpty()
        if (list.isEmpty() && mine) {
            Text(
                text = stringResource(R.string.fb_empty_owner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.forEach { report ->
            ReportCard(
                report = report,
                canReview = mine && report.status == "new",
                onReview = { status ->
                    scope.launch {
                        if (app.feedback.review(report.id, status)?.ok == true) tick++
                    }
                },
            )
        }
    }
}

/** Bug or suggestion, the words, and the button. */
@Composable
private fun ReportForm(onSend: (bug: Boolean, body: String, done: (Int, Boolean) -> Unit) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var bug by rememberSaveable { mutableStateOf(true) }
    var body by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    var good by remember { mutableStateOf(false) }
    val length = body.trim().length
    val canSend = !sending && length >= FeedbackRules.MIN && length <= FeedbackRules.MAX

    Text(
        text = stringResource(R.string.fb_empty_tester),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.fb_bug), bug) { bug = true }
        Choice(stringResource(R.string.fb_suggestion), !bug) { bug = false }
    }
    OutlinedTextField(
        value = body,
        onValueChange = { if (it.length <= FeedbackRules.MAX) body = it },
        placeholder = { Text(stringResource(if (bug) R.string.fb_hint_bug else R.string.fb_hint_idea)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(
            if (sending) R.string.fb_sending else R.string.fb_send,
        ),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (canSend) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = canSend) {
                sending = true
                note = null
                onSend(bug, body.trim()) { said, ok ->
                    sending = false
                    note = said
                    good = ok
                    if (ok) body = ""
                }
            }
            .padding(vertical = 12.dp),
    )
    note?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = if (good) oneDevsColors.live.solid else oneDevsColors.critical.solid,
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** One report, its state, and the developer's three answers while it is new. */
@Composable
private fun ReportCard(report: FeedbackReport, canReview: Boolean, onReview: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (report.status) {
        "accepted" -> oneDevsColors.live
        "confirmed" -> oneDevsColors.critical
        "dismissed" -> null
        else -> oneDevsColors.testing
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (report.isBug) R.string.fb_bug else R.string.fb_suggestion),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    when (report.status) {
                        "accepted" -> R.string.fb_status_accepted
                        "confirmed" -> R.string.fb_status_confirmed
                        "dismissed" -> R.string.fb_status_dismissed
                        else -> R.string.fb_status_new
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent?.solid ?: scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent?.tint ?: scheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(text = report.body, style = MaterialTheme.typography.bodyMedium)
        if (!report.mine) {
            report.author?.let {
                Text(
                    text = stringResource(R.string.fb_by, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (canReview) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReview("accepted") }) { Text(stringResource(R.string.fb_accept)) }
                if (report.isBug) {
                    TextButton(onClick = { onReview("confirmed") }) { Text(stringResource(R.string.fb_confirm)) }
                }
                TextButton(onClick = { onReview("dismissed") }) { Text(stringResource(R.string.fb_dismiss)) }
            }
        }
    }
}
