package com.devbangs.onedevs.data.plans

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devbangs.onedevs.OneDevsApplication
import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.notifications.DevBot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Says when a testing cycle has a new report.
 *
 * The server writes a report every fourth day of a cycle whether or not
 * anyone is looking; this asks every few hours and tells the owner about the
 * newest one they have not heard about. Only the newest: three reports
 * missed while the phone was off are one notification, not three.
 */
class InsightWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = quietly { work() }

    private suspend fun work(): Result {
        val app = applicationContext as? OneDevsApplication ?: return Result.success()
        app.account.ready.first { it }
        val me = app.account.session.value?.userId ?: return Result.success()
        val runs = app.ghostline.runs() ?: return Result.retry()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (run in runs.filter { it.isCycle }) {
            val newest = run.insights.maxByOrNull { it.day } ?: continue
            val key = "$me:${run.id}"
            if (newest.day <= prefs.getInt(key, 0)) continue
            DevBot.post(
                applicationContext,
                DevBot.Channel.LAUNCHES,
                run.id.hashCode(),
                applicationContext.getString(R.string.cy_notify_title, newest.day, run.title),
                applicationContext.getString(R.string.cy_notify_body, newest.testers, run.needed),
            )
            prefs.edit { putInt(key, newest.day) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "cycle-insights"
        private const val PREFS = "insights"

        /** Asks every six hours, when there is a network. Safe to call on every start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InsightWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
