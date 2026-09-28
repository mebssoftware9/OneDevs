package com.devbangs.onedevs.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How long an app was actually in front of someone.
 *
 * This is the evidence a test is paid on, and it was measured before it was
 * designed around: usage events are not filtered by package visibility, so
 * foreground time for any app is readable with this permission and without
 * QUERY_ALL_PACKAGES -- which a testing platform could never justify to Play.
 *
 * It is still a claim made by the device. The server decides the day, refuses
 * a second claim, and will one day want a Play Integrity verdict alongside it.
 */

/**
 * Whether the user has granted usage access.
 *
 * An appop rather than a runtime permission, so this is a question, not a
 * request -- there is no dialog to show, only Settings to open.
 * unsafeCheckOpNoThrow arrived in 29 and deprecated checkOpNoThrow, so each is
 * used on the levels where it is the right call.
 */
@Suppress("DEPRECATION")
fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } else {
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Where the user grants it. There is no in-app prompt for this one. */
fun usageAccessSettings(): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Seconds [packageName] spent in the foreground since [since].
 *
 * Summed from resume/pause pairs rather than from queryUsageStats totals,
 * because the totals are bucketed by day and cannot answer "in the last four
 * minutes". A session still open when this runs counts up to now, which is the
 * normal case: the tester has just switched back to OneDevs and the app they
 * were testing paused a moment ago.
 */
suspend fun foregroundSeconds(
    context: Context,
    packageName: String,
    since: Long,
): Int = withContext(Dispatchers.IO) {
    val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val events = usage.queryEvents(since, now)
    val event = UsageEvents.Event()

    var total = 0L
    var resumedAt = 0L
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.packageName != packageName) continue
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt = event.timeStamp
            UsageEvents.Event.ACTIVITY_PAUSED ->
                if (resumedAt > 0L) {
                    total += event.timeStamp - resumedAt
                    resumedAt = 0L
                }
        }
    }
    if (resumedAt > 0L) total += now - resumedAt
    (total / 1000L).toInt()
}

/**
 * A stable-enough name for this device.
 *
 * Resettable by a factory reset and scoped per signing key, so it is a speed
 * bump against one person claiming from several accounts rather than proof of
 * anything. Play Integrity is what turns it into evidence.
 */
@Suppress("HardwareIds")
fun deviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        .orEmpty()
        .ifBlank { "unknown-device" }
