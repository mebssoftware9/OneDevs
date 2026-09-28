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
import java.util.concurrent.TimeUnit

/**
 * Carries owed claims to the server, for as long as that takes.
 *
 * It runs outside the screen that created the claim, survives the process being
 * killed, and waits for a network rather than failing without one. A reply lost
 * in flight costs nothing: claim_test answers a repeat from the ledger, so
 * asking twice pays once and asking a hundred times still pays once.
 *
 * Only an answer removes a claim. Silence is not an answer, and it is the one
 * case that must never look like one.
 */
class ClaimWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OneDevsApplication ?: return Result.success()
        val outbox = app.claims
        outbox.load()

        // Whose claims these are matters more than it looks. Two accounts on
        // one device is the normal case for a developer testing their own
        // platform, and claim_test credits whoever is holding the token -- so
        // draining A's claim while B is signed in would pay B for A's work.
        val me = app.account.session.value?.userId ?: return Result.retry()
        val mine = outbox.waiting.value.filter { it.account == me }
        if (mine.isEmpty()) return Result.success()

        var silent = false
        for (claim in mine) {
            val answer = app.backend.claimTest(claim.listingId, claim.seconds, claim.device)
            if (answer == null) {
                // Never reached a server. The debt stands.
                silent = true
                continue
            }
            outbox.settle(claim, answer.claimed, answer.reason)
            if (answer.claimed) app.account.refreshBalance()
        }

        // Reloaded rather than assumed: a claim made while this was running is
        // owed too, and would otherwise wait for an event that never comes.
        outbox.load()
        val left = outbox.waiting.value.any { it.account == me }
        return if (silent || left) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "claims"

        /**
         * Asks for the outbox to be emptied. Cheap and safe to call often --
         * on every cold start, whenever the network returns, and the moment a
         * claim is written.
         */
        fun drain(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClaimWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
