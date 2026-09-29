package com.devbangs.onedevs.ui.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.claims.ClaimRecord
import com.devbangs.onedevs.data.claims.ClaimStatus
import com.devbangs.onedevs.data.claims.ClaimWorker
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.data.tests.REQUIRED_SECONDS
import com.devbangs.onedevs.data.tests.TestStage
import com.devbangs.onedevs.data.tests.TestStatus
import com.devbangs.onedevs.data.tests.decide
import com.devbangs.onedevs.data.tests.reasonText
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.data.usage.foregroundSeconds
import com.devbangs.onedevs.data.usage.hasUsageAccess
import com.devbangs.onedevs.data.usage.usageAccessSettings
import com.devbangs.onedevs.ui.board.openPlayListing
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.Waiting
import kotlinx.coroutines.launch

/**
 * How much of the thirty-two seconds has been done.
 *
 * It does not tick. Nothing accumulates while this screen is the thing being
 * looked at -- the time only accrues inside the app being tested -- so a
 * counting-down number here would be an animation impersonating a measurement.
 * It moves when there is new evidence, which is on the way back in.
 */
@Composable
private fun SecondsRing(seconds: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = (seconds.toFloat() / REQUIRED_SECONDS).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "testProgress",
    )
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(92.dp)) {
        CircularProgressIndicator(
            progress = { 1f },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 7.dp,
            gapSize = 0.dp,
            modifier = Modifier.size(92.dp),
        )
        CircularProgressIndicator(
            progress = { fraction },
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 7.dp,
            gapSize = 0.dp,
            modifier = Modifier.size(92.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seconds.coerceAtMost(REQUIRED_SECONDS).toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.test_of_seconds, REQUIRED_SECONDS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Reasons nothing on this phone can change; offering "check again" would be a tease. */
private val FINAL_REASONS = setOf("device_used", "own_app", "missing")

/**
 * A tester's side of a listing.
 *
 * What it shows comes from two records -- the claim this phone holds and what
 * the server says -- and never from what the screen last happened to be
 * doing. Leave and come back, restart the phone, open it tomorrow: a paid
 * test says paid, a claim waiting for a network says so, and a test this
 * phone cannot earn from says why before anyone spends thirty-two seconds.
 *
 * "Test now" reserves the reward on the server first. If the developer cannot
 * pay, or this phone has already been paid for this app, the tester is told
 * there and then rather than after the work.
 *
 * The app is opened through Play rather than launched directly: launching an
 * arbitrary package needs QUERY_ALL_PACKAGES, which Play reserves for a few
 * kinds of app. Foreground time is foreground time however the app started.
 */
@Composable
fun TestPanel(listing: Listing, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    val device = remember { deviceId(context) }

    val session by app.account.session.collectAsState()
    val me = session?.userId
    val records by app.claims.records.collectAsState()
    val local: ClaimRecord? = records.lastOrNull { it.account == me && it.listingId == listing.id }

    // What the server says, asked on open, whenever the local record changes
    // status, and on every way back into the screen.
    var remote by remember(listing.id) { mutableStateOf<TestStatus?>(null) }
    var loading by remember(listing.id) { mutableStateOf(true) }
    var offline by remember(listing.id) { mutableStateOf(false) }
    var ask by remember(listing.id) { mutableIntStateOf(0) }
    LaunchedEffect(listing.id, me, ask, local?.status) {
        loading = true
        when (val reply = app.tests.status(listing.id, device)) {
            is Reply.Answer -> {
                remote = reply.value
                offline = false
            }
            is Reply.Unreachable -> offline = true
            else -> offline = false
        }
        loading = false
    }

    val view = decide(local, remote, loading)

    // What the person is doing right now, which no server knows about.
    var needsAccess by rememberSaveable(listing.id) { mutableStateOf(false) }
    var starting by remember(listing.id) { mutableStateOf(false) }
    var startProblem by remember(listing.id) { mutableStateOf<Int?>(null) }
    var localStart by rememberSaveable(listing.id) { mutableLongStateOf(0L) }
    var seconds by rememberSaveable(listing.id) { mutableIntStateOf(0) }
    // Whether they have actually been away. Without it the measurement ran
    // the instant the Play sheet closed and read zero.
    var wentAway by remember(listing.id) { mutableStateOf(false) }

    val testing = view.stage == TestStage.Testing
    // The earlier of the two clocks: this phone's tap, and the server's
    // record of the session. A resumed test only has the second.
    val startedAt = listOfNotNull(localStart.takeIf { it > 0L }, view.startedAt).minOrNull() ?: 0L

    // A test resumed from an earlier visit is measured once on arrival, so
    // the ring shows what was already done instead of zero.
    LaunchedEffect(testing, startedAt) {
        if (testing && startedAt > 0L && hasUsageAccess(context)) {
            seconds = foregroundSeconds(context, listing.packageName, startedAt)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (testing) wentAway = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // They were just in Settings.
        if (needsAccess && hasUsageAccess(context)) needsAccess = false
        if (testing && wentAway && startedAt > 0L) {
            wentAway = false
            scope.launch { seconds = foregroundSeconds(context, listing.packageName, startedAt) }
        } else if (!testing) {
            ask++
        }
    }

    val done = testing && seconds >= REQUIRED_SECONDS
    val problem = startProblem
    val coins = view.coins.takeIf { it > 0 } ?: listing.reward

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (testing) {
            SecondsRing(seconds)
            Spacer(Modifier.height(12.dp))
        }

        val note: String? = when {
            needsAccess -> stringResource(R.string.test_access_body)
            starting -> stringResource(R.string.test_starting)
            problem != null -> stringResource(problem)
            testing && seconds == 0 -> stringResource(R.string.test_reserved, coins)
            testing && !done -> pluralStringResource(
                R.plurals.test_too_short,
                REQUIRED_SECONDS - seconds,
                REQUIRED_SECONDS - seconds,
            )
            done -> stringResource(R.string.test_ready)
            view.stage == TestStage.Confirming ->
                if (offline || (local?.attempts ?: 0) > 0) {
                    stringResource(R.string.test_confirming_offline, coins)
                } else {
                    stringResource(R.string.test_confirming, coins)
                }
            view.stage == TestStage.Paid -> stringResource(R.string.test_claimed_body, coins)
            view.stage == TestStage.Blocked -> stringResource(reasonText(view.reason))
            // Ready again after a refusal: say what happened last time.
            view.stage == TestStage.Ready && local?.status == ClaimStatus.Refused ->
                stringResource(reasonText(local.reason))
            else -> null
        }

        if (note != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DevBotMark(size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Offered, never required. Play gives no way to check whether a review
        // was left, so it cannot be a condition of the reward.
        if (done || view.stage == TestStage.Paid) {
            TextButton(onClick = { openPlayListing(context, listing.packageName) }) {
                Text(
                    text = stringResource(R.string.test_leave_review),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (view.stage == TestStage.Blocked && view.reason in FINAL_REASONS) return@Column

        val busy = starting || view.stage == TestStage.Checking || view.stage == TestStage.Confirming
        val label = when {
            needsAccess -> stringResource(R.string.test_allow_access)
            view.stage == TestStage.Paid -> stringResource(R.string.test_done)
            view.stage == TestStage.Blocked -> stringResource(R.string.test_check_again)
            done -> stringResource(R.string.test_claim, coins)
            testing -> stringResource(R.string.test_open_again)
            else -> stringResource(R.string.details_test_now)
        }

        Button(
            enabled = !busy && view.stage != TestStage.Paid,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                when {
                    needsAccess -> context.startActivity(usageAccessSettings())

                    view.stage == TestStage.Blocked -> {
                        startProblem = null
                        ask++
                    }

                    done -> scope.launch {
                        val account = me ?: return@launch
                        val reservation = view.session ?: return@launch
                        // Written down, not sent. ClaimWorker makes the server
                        // agree -- now if there is a network, later if not --
                        // and the screen follows the record, not the request.
                        app.claims.add(
                            ClaimRecord(
                                account = account,
                                listingId = listing.id,
                                title = listing.title,
                                seconds = seconds,
                                device = device,
                                coins = coins,
                                at = System.currentTimeMillis(),
                                session = reservation,
                            ),
                        )
                        ClaimWorker.drain(context, urgent = true)
                    }

                    testing -> openPlayListing(context, listing.packageName)

                    // Asked at the moment it is needed: the first point where
                    // the permission has a reason, which is what gets it granted.
                    !hasUsageAccess(context) -> needsAccess = true

                    else -> scope.launch {
                        starting = true
                        startProblem = null
                        when (val reply = app.tests.begin(listing.id, device)) {
                            is Reply.Answer -> {
                                val begun = reply.value
                                if (begun.ok) {
                                    remote = TestStatus(
                                        state = "open",
                                        reward = begun.reward,
                                        session = begun.session,
                                        startedAt = begun.startedAt,
                                        expiresAt = begun.expiresAt,
                                    )
                                    localStart = System.currentTimeMillis()
                                    seconds = 0
                                    openPlayListing(context, listing.packageName)
                                } else {
                                    remote = TestStatus(
                                        state = if (begun.reason == "already_paid") "paid" else begun.reason ?: "refused",
                                        reward = begun.reward.takeIf { it > 0 } ?: listing.reward,
                                    )
                                }
                            }
                            is Reply.Unreachable -> startProblem = R.string.test_unreachable
                            is Reply.SignedOut -> startProblem = R.string.test_signed_out
                            is Reply.Rejected -> startProblem = R.string.test_refused
                        }
                        starting = false
                    }
                }
            },
        ) {
            if (busy) {
                Waiting(size = 22.dp)
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
