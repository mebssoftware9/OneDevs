package com.devbangs.onedevs

import android.app.Application
import androidx.datastore.core.DataStoreFactory
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.DataStoreSessionHolder
import com.devbangs.onedevs.data.backend.SessionHolder
import com.devbangs.onedevs.data.backend.SessionSerializer
import com.devbangs.onedevs.data.listings.DataStoreListingRepository
import com.devbangs.onedevs.data.listings.ListingRepository
import com.devbangs.onedevs.data.listings.ListingsSerializer
import com.devbangs.onedevs.notifications.DevBot
import com.devbangs.onedevs.settings.ThemeStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        // Read before anything draws, so the first frame is already in the
        // right theme rather than flashing the wrong one and correcting.
        ThemeStore.load(this)
    }

    /**
     * The apps this developer has put up, held for as long as the process
     * lives. DataStore serialises its own writes, so one instance per file is
     * not a convenience -- a second would corrupt the first.
     */
    /**
     * The signed-in session, and the only thing that survives a cold start
     * about who this is. Its own file: a corrupt listings file should not sign
     * anyone out, and a sign-out should not touch their apps.
     */
    val sessions: SessionHolder by lazy {
        DataStoreSessionHolder(
            DataStoreFactory.create(
                serializer = SessionSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(filesDir, "session.json") },
            ),
        )
    }

    /** Who is signed in and what they hold, shared by every screen that asks. */
    val account: AccountState by lazy {
        AccountState(
            sessions = sessions,
            backend = backend,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /** Everything that leaves this device for OneDevs' own backend. */
    val backend: Backend by lazy {
        Backend(
            url = BuildConfig.SUPABASE_URL,
            key = BuildConfig.SUPABASE_KEY,
            sessions = sessions,
        )
    }

    val listings: ListingRepository by lazy {
        DataStoreListingRepository(
            DataStoreFactory.create(
                serializer = ListingsSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(filesDir, "listings.json") },
            ),
        )
    }
}
