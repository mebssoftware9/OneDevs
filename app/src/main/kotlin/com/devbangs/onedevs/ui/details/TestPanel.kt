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
import com.devbangs.onedevs.data.listings.Listing
import com.devbangs.onedevs.data.claims.ClaimWorker
import com.devbangs.onedevs.data.claims.PendingClaim
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.data.usage.foregroundSeconds
import com.devbangs.onedevs.data.usage.hasUsageAccess
import com.devbangs.onedevs.data.usage.usageAccessSettings
import com.devbangs.onedevs.ui.board.openPlayListing
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.Waiting
import kotlinx.coroutines.launch

/** Thirty-two seconds, matching the rule the database enforces. */
private const val RequiredSeconds = 32

private enum class Phase { Idle, NeedsAccess, Testing, Claiming, Claimed, Refused }

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
        targetValue = (seconds.toFloat() / RequiredSeconds).coerceIn(0f, 1f),
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
                text = seconds.coerceAtMost(RequiredSeconds).toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.test_of_seconds, RequiredSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A tester's side of a listing.
 *
 * The app is opened through Play rather than launched directly: launching an
 * arbitrary package needs the visibility QUERY_ALL_PACKAGES would buy, and
 * Play reserves that for device search, antivirus, file managers and browsers.
 * It costs nothing here -- foreground time is foreground time however the app
 * was started, and an app that was in front of someone is installed by
 * definition, which is more than "installed" would have told us.
 *
 * Time is never reset by coming back early. Someone who opens the app, gets
 * interrupted, and opens it again has both visits counted, because both were
 * real.
 */
@Composable
fun TestPanel(listing: Listing, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()

    // Primitives rather than a sealed state, so a rotation mid-test does not
    // throw away the clock someone has already spent half a minute on.
    var phase by rememberSaveable(listing.id) { mutableStateOf(Phase.Idle) }
    var startedAt by rememberSaveable(listing.id) { mutableLongStateOf(0L) }
    var seconds by rememberSaveable(listing.id) { mutableIntStateOf(0) }
    var coins by rememberSaveable(listing.id) { mutableIntStateOf(0) }
    var refusal by rememberSaveable(listing.id) { mutableStateOf<String?>(null) }

    // Whether they have actually been away. Without this the measurement ran
    // the instant the Play sheet closed -- before anyone had gone anywhere --
    // read zero seconds, and reported that all thirty-two were still to do.
    var wentAway by remember(listing.id) { mutableStateOf(false) }

    val saidAlready = stringResource(R.string.test_already)
    val saidUnfunded = stringResource(R.string.test_unfunded)
    val saidFull = stringResource(R.string.test_full)
    val saidRefused = stringResource(R.string.test_refused)
    val saidUnreachable = stringResource(R.string.test_unreachable)

    // The server's answer, whenever it comes. It may arrive seconds from now,
    // or after this screen has been closed and opened again, or tomorrow on a
    // train -- and the only difference that makes is when the number moves.
    LaunchedEffect(listing.id) {
        app.claims.settled.collect { settlement ->
            if (settlement.claim.listingId != listing.id) return@collect
            if (settlement.paid) {
                coins = settlement.claim.coins
                phase = Phase.Claimed
                app.account.refreshBalance()
            } else {
                refusal = when (settlement.reason) {
                    "device_used" -> saidAlready
                    "unfunded" -> saidUnfunded
                    "full" -> saidFull
                    else -> saidRefused
                }
                phase = Phase.Refused
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (phase == Phase.Testing) wentAway = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // They were just in Settings. Asking again is the whole reason the
        // button used to be stuck saying "Allow usage access" until a restart.
        if (phase == Phase.NeedsAccess && hasUsageAccess(context)) {
            phase = Phase.Idle
        }
        if (phase == Phase.Testing && wentAway && startedAt > 0L) {
            wentAway = false
            scope.launch {
                seconds = foregroundSeconds(context, listing.packageName, startedAt)
            }
        }
    }

    val done = phase == Phase.Testing && seconds >= RequiredSeconds

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (phase == Phase.Testing) {
            SecondsRing(seconds)
            Spacer(Modifier.height(12.dp))
        }

        val note: String? = when {
            phase == Phase.NeedsAccess -> stringResource(R.string.test_access_body)
            phase == Phase.Testing && seconds == 0 -> stringResource(R.string.test_waiting)
            phase == Phase.Testing && !done -> pluralStringResource(
                R.plurals.test_too_short,
                RequiredSeconds - seconds,
                RequiredSeconds - seconds,
            )
            done -> stringResource(R.string.test_ready)
            phase == Phase.Claimed -> stringResource(R.string.test_claimed_body, coins)
            phase == Phase.Refused -> refusal
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

        val label = when {
            phase == Phase.NeedsAccess -> stringResource(R.string.test_allow_access)
            // Asked before `done`, because thirty-two seconds stay done after
            // the reward is claimed and the button must stop offering it.
            phase == Phase.Claimed -> stringResource(R.string.test_done)
            done -> stringResource(R.string.test_claim, listing.reward)
            phase == Phase.Testing -> stringResource(R.string.test_open_again)
            else -> stringResource(R.string.details_test_now)
        }

        // Offered, never required. Play gives no way to check whether a review
        // was left, so making it a condition of the reward would mean inventing
        // a check that does not exist -- and a developer's real prize from a
        // closed test is the written feedback, so it is worth asking for.
        if (done || phase == Phase.Claimed) {
            TextButton(onClick = { openPlayListing(context, listing.packageName) }) {
                Text(
                    text = stringResource(R.string.test_leave_review),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Button(
            enabled = phase != Phase.Claiming && phase != Phase.Claimed,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                when {
                    phase == Phase.NeedsAccess ->
                        context.startActivity(usageAccessSettings())

                    // Already recorded. Tapping again is not a second reward,
                    // and must not read like one.
                    phase == Phase.Claimed -> Unit

                    done -> scope.launch {
                        val me = app.account.session.value?.userId ?: return@launch
                        // Written down, not sent. The reward is owed from this
                        // moment; ClaimWorker is what makes the server agree --
                        // now if there is a network, later if there is not.
                        // Nothing on this path waits for a socket, because the
                        // instant a person finishes work is the worst possible
                        // moment to ask them to have a connection.
                        app.claims.add(
                            PendingClaim(
                                account = me,
                                listingId = listing.id,
                                title = listing.title,
                                seconds = seconds,
                                device = deviceId(context),
                                coins = listing.reward,
                                at = System.currentTimeMillis(),
                            ),
                        )
                        coins = listing.reward
                        phase = Phase.Claimed
                        ClaimWorker.drain(context)
                    }

                    else -> {
                        // Asked at the moment it is needed rather than at
                        // launch: this is the first point where the permission
                        // has a reason, and a reason is what gets it granted.
                        if (!hasUsageAccess(context)) {
                            phase = Phase.NeedsAccess
                        } else {
                            if (startedAt == 0L) startedAt = System.currentTimeMillis()
                            phase = Phase.Testing
                            openPlayListing(context, listing.packageName)
                        }
                    }
                }
            },
        ) {
            if (phase == Phase.Claiming) {
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
