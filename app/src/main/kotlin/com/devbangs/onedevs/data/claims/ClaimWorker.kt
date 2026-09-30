package com.devbangs.onedevs.data.claims

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.tests.FinishResult
import com.devbangs.onedevs.data.tests.reasonText
import com.devbangs.onedevs.notifications.DevBot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** What one attempt at a claim came to. */
internal sealed interface Outcome {
    data class Paid(val coins: Int) : Outcome
    data class Refused(val reason: String) : Outcome

    /** No answer. The claim stands and is asked again later. */
    data object Later : Outcome

    /** The session ended. The claim waits for its account to sign in again. */
    data object SignedOut : Outcome
}

/**
 * Turns a server reply into what to do with the claim. Pure, so every branch
 * is pinned by a test: this is the function that decides whether someone is
 * told they were paid.
 */
internal fun outcome(reply: Reply<FinishResult>): Outcome = when (reply) {
    is Reply.Answer -> if (reply.value.succeeded) {
        Outcome.Paid(reply.value.coins)
    } else {
        Outcome.Refused(reply.value.reason ?: "refused")
    }
    is Reply.Unreachable -> Outcome.Later
    is Reply.SignedOut -> Outcome.SignedOut
    // The server understood and said no in a way asking again will not
    // change. Recorded and shown, never retried forever.
    is Reply.Rejected -> Outcome.Refused("rejected")
}

/**
 * Carries owed claims to the server, for as long as that takes.
 *
 * It runs outside the screen that created the claim, survives the process
 * being killed, and waits for a network rather than failing without one. A
 * reply lost in flight costs nothing: finish_test answers a repeat with the
 * first answer, so asking twice pays once.
 *
 * Only an answer settles a claim, and every answer is kept and shown -- on the
 * test screen, and as a notification when the tester has moved on. Silence is
 * the one outcome that must never look like one.
 */
class ClaimWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    // A claim retried in the background has no one watching a spinner.
    override suspend fun doWork(): Result =
        com.devbangs.onedevs.data.backend.quietly { work() }

    private suspend fun work(): Result {
        val app = applicationContext as? OneDevsApplication ?: return Result.success()
        val outbox = app.claims
        outbox.load()

        // Wait for the stored session to be read rather than mistaking "not
        // read yet" for "signed out" on a cold start.
        app.account.ready.first { it }

        // Whose claims these are matters: finish_test credits whoever holds
        // the token, so draining A's claim while B is signed in would pay B
        // for A's work. Another account's claims wait for that account.
        val me = app.account.session.value?.userId ?: return Result.success()
        val mine = outbox.waiting.value.filter { it.account == me }
        if (mine.isEmpty()) return Result.success()

        var unanswered = false
        var anyPaid = false
        for (claim in mine) {
            when (val result = attempt(app, claim)) {
                is Outcome.Paid -> {
                    app.account.credit(result.coins)
                    outbox.settle(claim.id, paid = true, coins = result.coins, reason = null)
                    anyPaid = true
                    // Said out loud only when it took a while. A tester still
                    // on the screen has already seen it.
                    if (System.currentTimeMillis() - claim.at > QUIET_MS) tellPaid(claim, result.coins)
                }
                is Outcome.Refused -> {
                    outbox.settle(claim.id, paid = false, coins = null, reason = result.reason)
                    tellRefused(claim, result.reason)
                }
                Outcome.Later -> {
                    outbox.attempted(claim.id)
                    unanswered = true
                }
                // Holding the question is right; retrying it is not. The next
                // sign-in asks for a drain.
                Outcome.SignedOut -> return Result.success()
            }
        }
        if (anyPaid) app.account.refreshBalance()

        // Reloaded rather than assumed: a claim made while this was running
        // is owed too.
        outbox.load()
        val left = outbox.waiting.value.any { it.account == me }
        return if (unanswered || left) Result.retry() else Result.success()
    }

    /**
     * One claim, asked up to three times in quick succession before giving
     * the question back to WorkManager. A dropped packet should cost seconds,
     * not an exponential backoff.
     */
    private suspend fun attempt(app: OneDevsApplication, claim: ClaimRecord): Outcome {
        repeat(QUICK_TRIES) { tried ->
            val reply = if (claim.session != null) {
                app.tests.finish(claim.session, claim.seconds)
            } else {
                app.tests.claimLegacy(claim.listingId, claim.seconds, claim.device)
            }
            val result = outcome(reply)
            if (result != Outcome.Later) return result
            if (tried < QUICK_TRIES - 1) delay(1_000L * (tried * 2 + 1))
        }
        return Outcome.Later
    }

    private fun tellPaid(claim: ClaimRecord, coins: Int) {
        val context = applicationContext
        DevBot.post(
            context,
            DevBot.Channel.DEVCOINS,
            claim.id.hashCode(),
            context.getString(R.string.notif_reward_paid_title),
            context.getString(R.string.notif_reward_paid_body, coins, claim.title),
        )
    }

    private fun tellRefused(claim: ClaimRecord, reason: String) {
        val context = applicationContext
        DevBot.post(
            context,
            DevBot.Channel.DEVCOINS,
            claim.id.hashCode(),
            context.getString(R.string.notif_reward_refused_title, claim.title),
            context.getString(reasonText(reason)),
        )
    }

    companion object {
        private const val NAME = "claims"
        private const val QUICK_TRIES = 3

        /** Claims settled within this long need no notification: the tester was there. */
        private const val QUIET_MS = 60_000L

        /**
         * Asks for the outbox to be emptied. Cheap and safe to call often.
         *
         * [urgent] is for a claim just written: it replaces a drain that is
         * sitting in backoff, so a fresh claim is asked now rather than behind
         * an old one's growing delay. Replacing mid-request is safe, because
         * finish_test pays a session once however often it is asked.
         */
        fun drain(context: Context, urgent: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ClaimWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                if (urgent) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
