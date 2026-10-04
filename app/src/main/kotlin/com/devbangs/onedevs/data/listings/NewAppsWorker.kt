package com.devbangs.onedevs.data.listings

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
import com.devbangs.onedevs.data.usage.deviceId
import com.devbangs.onedevs.notifications.DevBot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Tells a member when new apps reach the Board that they can test for
 * DevCoins.
 *
 * Reads the same boards the app shows -- which already leave out apps this
 * phone has been paid for and apps that cannot pay -- and remembers the
 * newest app it has told this account about, so each app is announced once.
 * The first run only learns where the Board is: someone who just installed
 * is not told about every app at once.
 */
class NewAppsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = quietly { work() }

    private suspend fun work(): Result {
        val app = applicationContext as? OneDevsApplication ?: return Result.success()
        app.account.ready.first { it }
        val me = app.account.session.value?.userId ?: return Result.success()
        val device = deviceId(applicationContext)
        val boards = Channel.entries.map { app.listings.boardOrNull(it, device) ?: return Result.retry() }
        val apps = boards.flatten().filter { it.owner != me && it.createdAt > 0L }

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "seen:$me"
        val newest = apps.maxOfOrNull { it.createdAt } ?: return Result.success()
        if (!prefs.contains(key)) {
            prefs.edit { putLong(key, newest) }
            return Result.success()
        }
        val seen = prefs.getLong(key, newest)
        val fresh = apps.filter { it.createdAt > seen }.distinctBy { it.id }
        if (fresh.isEmpty()) return Result.success()

        val first = fresh.maxByOrNull { if (it.spotlight) Int.MAX_VALUE else it.reward } ?: return Result.success()
        val context = applicationContext
        val (title, body) = when {
            fresh.size == 1 && first.spotlight -> context.getString(R.string.new_app_spotlight_title, first.title) to
                context.getString(R.string.new_app_body, first.reward)
            fresh.size == 1 -> context.getString(R.string.new_app_title, first.title) to
                context.getString(R.string.new_app_body, first.reward)
            else -> context.resources.getQuantityString(R.plurals.new_apps_title, fresh.size, fresh.size) to
                context.getString(R.string.new_apps_body, first.title)
        }
        DevBot.post(context, DevBot.Channel.BOARD, NOTIFICATION_ID, title, body)
        prefs.edit { putLong(key, newest) }
        return Result.success()
    }

    companion object {
        private const val NAME = "new-apps"
        private const val PREFS = "new_apps"
        private const val NOTIFICATION_ID = 7301

        /** Looks every three hours, the same rhythm as mission reminders. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewAppsWorker>(3, TimeUnit.HOURS)
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
