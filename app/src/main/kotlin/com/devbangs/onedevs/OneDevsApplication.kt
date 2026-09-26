package com.devbangs.onedevs

import android.app.Application
import com.devbangs.onedevs.notifications.DevBot

/**
 * Registers DevBot's channels at install rather than at first notification.
 *
 * The difference matters. Channels created lazily by the first post do not
 * exist in system settings until something has already interrupted the user --
 * so the one moment they most want to turn a category off is the first moment
 * they are able to. Creating them here means all three are there to be tuned
 * before DevBot has said anything, which is also the only order in which
 * "silence rewards, keep mission days" is a choice rather than a reaction.
 *
 * createNotificationChannel is idempotent: calling it on every cold start
 * updates the title and description from the current locale and changes
 * nothing the user has set.
 */
class OneDevsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DevBot.ensureChannels(this)
    }
}
