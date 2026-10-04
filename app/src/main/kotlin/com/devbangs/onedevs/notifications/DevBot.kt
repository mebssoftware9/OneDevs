package com.devbangs.onedevs.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.devbangs.onedevs.MainActivity
import com.devbangs.onedevs.R

/**
 * DevBot is the only part of OneDevs that speaks to someone while they are away
 * from the app. Everything it says is triggered by something that actually
 * happened — a mission clock, a tester joining, DevCoins moving — so there is
 * no path here for posting engagement bait.
 *
 * Channels are split by what the message is about, not by urgency, so a tester
 * can silence reward chatter and still hear about a mission running out of days.
 */
object DevBot {

    enum class Channel(
        val id: String,
        @StringRes val title: Int,
        @StringRes val description: Int,
    ) {
        MISSIONS("missions", R.string.channel_missions, R.string.channel_missions_desc),
        LAUNCHES("launches", R.string.channel_launches, R.string.channel_launches_desc),
        DEVCOINS("devcoins", R.string.channel_devcoins, R.string.channel_devcoins_desc),
        BOARD("board", R.string.channel_board, R.string.channel_board_desc),
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        Channel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(
                    channel.id,
                    context.getString(channel.title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(channel.description) }
            )
        }
    }

    /** True when the system will actually deliver what DevBot posts. */
    fun canSpeak(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun post(context: Context, channel: Channel, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        // A tap opens the app where it was, or starts it.
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel.id)
            .setContentIntent(open)
            .setSmallIcon(R.drawable.ic_robot_fill)
            .setColor(ContextCompat.getColor(context, R.color.devbot_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
