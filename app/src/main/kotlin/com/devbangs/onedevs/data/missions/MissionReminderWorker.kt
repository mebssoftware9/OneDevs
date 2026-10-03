package com.devbangs.onedevs.data.missions

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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Once a day, while a mission runs, reminds a member who still has apps to
 * test today. Mission days are UTC days on the server, so the reminder comes
 * in the second half of the UTC day, with hours still left to do them, and
 * never twice for the same mission on the same day.
 */
class MissionReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = quietly { work() }

    private suspend fun work(): Result {
        val app = applicationContext as? OneDevsApplication ?: return Result.success()
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        if (now.hour < FROM_HOUR_UTC) return Result.success()
        app.account.ready.first { it }
        val me = app.account.session.value?.userId ?: return Result.success()
        val missions = app.missions.mine() ?: return Result.retry()

        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        missions
            .filter { it.member && it.stage == MissionStage.Running }
            .forEach { mission ->
                val tasks = mission.others.count { it.seat >= 1 }
                val done = mission.others.count { it.seat >= 1 && mission.done(it) }
                val key = "$me:${mission.id}"
                if (tasks == 0 || done >= tasks || prefs.getString(key, null) == today) return@forEach
                DevBot.post(
                    applicationContext,
                    DevBot.Channel.MISSIONS,
                    mission.id.hashCode(),
                    applicationContext.getString(R.string.mission_remind_title, mission.name),
                    applicationContext.getString(R.string.mission_remind_body, tasks - done, tasks),
                )
                prefs.edit { putString(key, today) }
            }
        return Result.success()
    }

    companion object {
        private const val NAME = "mission-reminders"
        private const val PREFS = "mission_reminders"
        private const val FROM_HOUR_UTC = 12

        /** Looks every three hours; posts at most once a day per mission. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MissionReminderWorker>(3, TimeUnit.HOURS)
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
