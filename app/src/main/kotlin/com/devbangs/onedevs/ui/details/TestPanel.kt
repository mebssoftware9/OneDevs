package com.devbangs.onedevs.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.data.usage.foregroundSeconds
import com.devbangs.onedevs.data.usage.hasUsageAccess
import com.devbangs.onedevs.data.usage.usageAccessSettings
import com.devbangs.onedevs.ui.board.openPlayListing
import com.devbangs.onedevs.ui.components.DevBotMark
import com.devbangs.onedevs.ui.components.Waiting
import kotlinx.coroutines.launch

/** Where a test has got to. */
private sealed interface TestState {
    data object Idle : TestState

    /** Usage access has not been granted, so nothing can be observed yet. */
    data object NeedsAccess : TestState

    /** The app was opened at this moment; time is accumulating. */
    data class Open(val startedAt: Long) : TestState

    /** Came back, but not enough of it happened yet. Keeps the clock running. */
    data class Short(val startedAt: Long, val seconds: Int) : TestState

    data class Ready(val seconds: Int) : TestState
    data object Claiming : TestState
    data class Claimed(val coins: Int) : TestState
    data class Refused(val reason: String) : TestState
}

/** Thirty-two seconds, matching the rule the database enforces. */
private const val RequiredSeconds = 32

/**
 * A tester's side of a listing.
 *
 * The app is opened through Play rather than launched directly: launching an
 * arbitrary package needs the visibility QUERY_ALL_PACKAGES would buy, which a
 * testing platform cannot justify to Play. It makes no difference to the
 * measurement -- foreground time is foreground time however the app was
 * started.
 *
 * Time is never reset by coming back early. Someone who opens the app, gets
 * interrupted, and opens it again should have both visits counted, because
 * both were real.
 */
@Composable
fun TestPanel(listing: Listing, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as OneDevsApplication
    val scope = rememberCoroutineScope()
    var state by remember(listing.id) { mutableStateOf<TestState>(TestState.Idle) }

    // Read at composition, not inside the click. Resources fetched through the
    // context in a callback are not configuration-aware, so changing language
    // mid-session would leave the old wording behind.
    val saidAlready = stringResource(R.string.test_already)
    val saidUnfunded = stringResource(R.string.test_unfunded)
    val saidFull = stringResource(R.string.test_full)
    val saidRefused = stringResource(R.string.test_refused)
    val saidUnreachable = stringResource(R.string.test_unreachable)

    // Measured on the way back in, which is the only moment the answer can have
    // changed. Nothing polls while the tester is away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val current = state
        val startedAt = when (current) {
            is TestState.Open -> current.startedAt
            is TestState.Short -> current.startedAt
            else -> null
        }
        if (startedAt != null) {
            scope.launch {
                val seconds = foregroundSeconds(context, listing.packageName, startedAt)
                state = if (seconds >= RequiredSeconds) {
                    TestState.Ready(seconds)
                } else {
                    TestState.Short(startedAt, seconds)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val note: String? = when (val s = state) {
            is TestState.NeedsAccess -> stringResource(R.string.test_access_body)
            is TestState.Open -> stringResource(R.string.test_waiting)
            is TestState.Short -> pluralStringResource(
                R.plurals.test_too_short,
                RequiredSeconds - s.seconds,
                RequiredSeconds - s.seconds,
            )
            is TestState.Claimed -> stringResource(R.string.test_claimed_body, s.coins)
            is TestState.Refused -> s.reason
            else -> null
        }

        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevBotMark(size = 28.dp)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        val label = when (state) {
            TestState.Idle -> stringResource(R.string.details_test_now)
            TestState.NeedsAccess -> stringResource(R.string.test_allow_access)
            is TestState.Open, is TestState.Short -> stringResource(R.string.test_open_again)
            is TestState.Ready -> stringResource(R.string.test_claim, listing.reward)
            TestState.Claiming -> ""
            is TestState.Claimed -> stringResource(R.string.test_done)
            is TestState.Refused -> stringResource(R.string.details_test_now)
        }

        Button(
            enabled = state !is TestState.Claiming && state !is TestState.Claimed,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                when (val s = state) {
                    TestState.NeedsAccess -> context.startActivity(usageAccessSettings())

                    is TestState.Ready -> scope.launch {
                        state = TestState.Claiming
                        val result = app.backend.claimTest(
                            listingId = listing.id,
                            seconds = s.seconds,
                            device = deviceId(context),
                        )
                        state = when {
                            result == null -> TestState.Refused(saidUnreachable)
                            result.claimed -> {
                                // The top bar is watching the same state, so the
                                // count moves without this screen telling it to.
                                app.account.refreshBalance()
                                TestState.Claimed(result.coins)
                            }
                            else -> TestState.Refused(
                                when (result.reason) {
                                    "already" -> saidAlready
                                    "unfunded" -> saidUnfunded
                                    "full" -> saidFull
                                    else -> saidRefused
                                },
                            )
                        }
                    }

                    else -> {
                        // Asked at the moment it is needed rather than at
                        // launch: this is the first point where the permission
                        // has a reason, and a reason is what makes it grantable.
                        if (!hasUsageAccess(context)) {
                            state = TestState.NeedsAccess
                        } else {
                            val startedAt = when (s) {
                                is TestState.Short -> s.startedAt
                                else -> System.currentTimeMillis()
                            }
                            state = TestState.Open(startedAt)
                            openPlayListing(context, listing.packageName)
                        }
                    }
                }
            },
        ) {
            if (state is TestState.Claiming) {
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
