package com.devbangs.onedevs.notifications

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.devbangs.onedevs.ads.Ads
import kotlinx.coroutines.flow.first

private const val PREFS = "devbot"
private const val ASKED = "asked_post_notifications"

/** The permission's name, spelled out: the constant only exists from Android 13. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * Asks once, from Android 13, for permission to post notifications.
 *
 * Without it every DevBot message -- a mission day running out, DevCoins
 * arriving, a cycle's report -- is dropped by the system without a trace.
 * Asked after sign-in, once the launch ad has closed, so it is the only thing
 * on screen; and once only. Someone who says no can still turn it on from
 * Profile, which opens the system's own notification settings.
 */
@Composable
fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (DevBot.canSpeak(context) || prefs.getBoolean(ASKED, false)) return@LaunchedEffect
        Ads.holding.first { !it }
        prefs.edit { putBoolean(ASKED, true) }
        launcher.launch(POST_NOTIFICATIONS)
    }
}
