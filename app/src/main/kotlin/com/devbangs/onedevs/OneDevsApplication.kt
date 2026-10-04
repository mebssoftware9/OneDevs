package com.devbangs.onedevs

import android.app.Application
import androidx.datastore.core.DataStoreFactory
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.DataStoreSessionHolder
import com.devbangs.onedevs.data.backend.SessionHolder
import com.devbangs.onedevs.data.backend.SessionSerializer
import com.devbangs.onedevs.data.claims.ClaimOutbox
import com.devbangs.onedevs.data.claims.ClaimWorker
import com.devbangs.onedevs.data.plans.InsightWorker
import com.devbangs.onedevs.data.listings.RemoteListingRepository
import com.devbangs.onedevs.data.missions.MissionRepository
import com.devbangs.onedevs.data.tests.TestRepository
import com.devbangs.onedevs.data.wallet.WalletRepository
import com.devbangs.onedevs.data.net.Connectivity
import com.devbangs.onedevs.notifications.DevBot
import com.devbangs.onedevs.settings.ThemeStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        // Anything owed from a previous run is owed now. Asking on every cold
        // start costs nothing when the outbox is empty, and is the difference
        // between a reward arriving late and a reward never arriving.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Handshake with the server while the splash is up.
        scope.launch { backend.warmUp() }
        scope.launch { claims.load() }
        // A purchase whose confirmation was lost on a previous run is sent
        // again once someone is signed in: as soon as the stored session has
        // loaded, and after every sign-in. Run at once it raced the session
        // and, finding nobody signed in yet, did nothing.
        scope.launch {
            account.session.map { it?.userId }.distinctUntilChanged().filterNotNull().collect {
                billing.restore()
            }
        }
        ClaimWorker.drain(this)
        // A testing cycle's reports arrive whether or not the app is open.
        InsightWorker.schedule(this)
        // New apps on the Board, once each, while the app is closed.
        com.devbangs.onedevs.data.listings.NewAppsWorker.schedule(this)
        // A member with apps left to test today hears about it once.
        com.devbangs.onedevs.data.missions.MissionReminderWorker.schedule(this)
        // A claim waits for the account that earned it. Signing in is the
        // moment that account's claims can finally be asked about.
        scope.launch {
            account.session.map { it?.userId }.distinctUntilChanged().filterNotNull().collect {
                ClaimWorker.drain(this@OneDevsApplication)
            }
        }
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

    /**
     * Tests that have been earned and not yet acknowledged. Survives the
     * process, so a reward is never lost to a connection that dropped between
     * the work being done and the server hearing about it.
     */
    val claims: ClaimOutbox by lazy { ClaimOutbox(this) }

    /** Whether anything can reach the server at all. */
    val network: Connectivity by lazy { Connectivity(this) }

    /** Who is signed in and what they hold, shared by every screen that asks. */
    val account: AccountState by lazy {
        AccountState(
            sessions = sessions,
            backend = backend,
            online = network.online,
            owed = claims.waiting,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /** Everything that leaves this device for OneDevs' own backend. */
    val backend: Backend by lazy {
        Backend(
            url = BuildConfig.SUPABASE_URL,
            key = BuildConfig.SUPABASE_KEY,
            sessions = sessions,
            attestor = com.devbangs.onedevs.data.integrity.PlayIntegrityAttestor(
                context = this,
                cloudProject = BuildConfig.PLAY_INTEGRITY_PROJECT,
            ),
        )
    }

    val listings: RemoteListingRepository by lazy {
        RemoteListingRepository(
            backend = backend,
            account = account,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /**
     * The Board, cached on disk and in memory. Your own listings changing, or
     * a claim settling, marks it stale so the next visit re-reads it.
     */
    val board: com.devbangs.onedevs.data.listings.BoardStore by lazy {
        com.devbangs.onedevs.data.listings.BoardStore(
            context = this,
            backend = backend,
            listings = listings,
            account = account,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            changes = listOf(listings.listings, claims.records),
        )
    }

    val missions: MissionRepository by lazy { MissionRepository(backend) }

    /** The account's plan, and the one app a free Lab is kept to. */
    val plans: com.devbangs.onedevs.data.plans.PlanStore by lazy {
        com.devbangs.onedevs.data.plans.PlanStore(
            context = this,
            backend = backend,
            account = account,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    /** Play Billing. Purchases are only ever confirmed by the server. */
    val billing: com.devbangs.onedevs.data.plans.Billing by lazy {
        com.devbangs.onedevs.data.plans.Billing(
            context = this,
            backend = backend,
            account = account,
            plans = plans,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    val ghostline: com.devbangs.onedevs.data.plans.GhostlineRepository by lazy {
        com.devbangs.onedevs.data.plans.GhostlineRepository(backend)
    }

    val feedback: com.devbangs.onedevs.data.feedback.FeedbackRepository by lazy {
        com.devbangs.onedevs.data.feedback.FeedbackRepository(backend)
    }

    val badges: com.devbangs.onedevs.data.badges.BadgeRepository by lazy {
        com.devbangs.onedevs.data.badges.BadgeRepository(backend)
    }

    /** Starting, checking and finishing tests: the questions that move DevCoins. */
    val tests: TestRepository by lazy { TestRepository(backend) }

    val wallet: WalletRepository by lazy { WalletRepository(backend) }
}
