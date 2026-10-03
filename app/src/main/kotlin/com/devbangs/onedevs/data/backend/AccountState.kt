package com.devbangs.onedevs.data.backend

import com.devbangs.onedevs.data.claims.ClaimRecord
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * What is known about the balance.
 *
 * Four states, because one nullable number had to mean four things and could
 * only say one. "Not asked", "asking", "it is 12" and "we asked and could not
 * find out" all showed as null, so the coin spun forever on a request that had
 * already given up.
 */
sealed interface Balance {
    /** Nobody signed in, or nothing asked yet. */
    data object Unknown : Balance

    data object Loading : Balance

    data class Known(val coins: Int) : Balance

    /** Asked, and could not be answered. Not a number, and not still working. */
    data object Unavailable : Balance
}

/**
 * Who is signed in, and what they have, held once for the whole app.
 *
 * Both the top bar and the account card need these two facts. Left to load
 * them separately they would disagree -- signing in on Profile would leave the
 * bar showing a stranger's glyph and a stale count until the next cold start.
 */
class AccountState(
    private val sessions: SessionHolder,
    private val backend: Backend,
    private val online: StateFlow<Boolean>,
    private val owed: StateFlow<List<ClaimRecord>>,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _balance = MutableStateFlow<Balance>(Balance.Unknown)

    /**
     * What this developer has, counting work that is finished but not yet
     * acknowledged by the server.
     *
     * A test that has been done is owed the moment it is done. Waiting for a
     * round trip before the number moves would make the platform look broken
     * on exactly the connections it most needs to work on -- and the coins are
     * not a guess: the seconds were measured, the listing was funded when the
     * board served it, and claim_test is the thing that can still say no. If
     * it does, the settlement takes them back and says why.
     *
     * Scoped to the account that earned them: pending claims belonging to
     * someone else who used this device are not this developer's balance.
     */
    val balance: StateFlow<Balance> = combine(_balance, owed, _session) { held, pending, who ->
        val mine = pending.filter { it.account == who?.userId }.sumOf { it.coins }
        if (held is Balance.Known && mine > 0) Balance.Known(held.coins + mine) else held
    }.stateIn(scope, SharingStarted.Eagerly, Balance.Unknown)

    /**
     * False until the stored session has been looked for. Without it, "no
     * session yet" and "signed out" are the same value, and every cold start
     * shows a signed-in developer the door for a frame.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val asking = Mutex()

    init {
        scope.launch {
            // A corrupt session file must not leave anyone staring at a splash
            // screen forever. Unreadable means signed out, which is one tap to
            // recover; a frozen launch is not recoverable at all.
            _session.value = try {
                sessions.current()
            } catch (e: IOException) {
                null
            }
            _ready.value = true
            refreshBalance()
        }

        // Coming back from a tunnel is the most likely moment for a balance
        // that could not be read to become readable. Asking then is cheaper
        // and kinder than asking on a timer.
        scope.launch {
            // StateFlow already conflates, so no operator is needed here.
            online.collect { up ->
                if (up && _balance.value !is Balance.Known) refreshBalance()
            }
        }
    }

    /**
     * Asks the server what the balance is, and asks again if it cannot.
     *
     * One attempt was a bug rather than a shortcut: a single timeout left the
     * balance unknown until the app was killed. Three attempts and then a
     * definite answer either way -- a number, the number we already had, or a
     * plain statement that it could not be read. Never an endless spinner.
     */
    suspend fun refreshBalance() {
        if (_session.value == null) {
            _balance.value = Balance.Unknown
            return
        }
        // Several screens can ask at once. One asks; the rest get its answer.
        if (!asking.tryLock()) return
        try {
            val previous = _balance.value
            if (previous !is Balance.Known) _balance.value = Balance.Loading

            var answer: Int? = null
            var attempt = 0
            while (answer == null && attempt < 3) {
                // The balance chip is never what anyone is waiting on.
                answer = quietly { backend.balance() }
                if (answer == null) {
                    attempt++
                    if (attempt < 3) delay(1500L * attempt)
                }
            }

            _balance.value = when {
                answer != null -> Balance.Known(answer)
                // A number read a minute ago is better company than a dash.
                previous is Balance.Known -> previous
                else -> Balance.Unavailable
            }
        } finally {
            asking.unlock()
        }
    }

    /**
     * Runs its own work in its own scope, and this is not a style choice.
     *
     * Publishing the session flips the gate, which disposes the sign-in screen,
     * which cancels the scope the caller was using -- so a balance fetched on
     * the caller's coroutine was killed by the state change that started it.
     */
    fun onSignedIn(session: Session) {
        _session.value = session
        scope.launch { refreshBalance() }
    }

    /** Deletes the account on the server; true once it is gone and signed out here. */
    suspend fun deleteAccount(): Boolean {
        val deleted = try {
            backend.deleteAccount()
        } catch (e: IOException) {
            false
        }
        if (deleted) {
            _session.value = null
            _balance.value = Balance.Unknown
        }
        return deleted
    }

    fun signOut() {
        _session.value = null
        _balance.value = Balance.Unknown
        scope.launch { backend.signOut() }
    }

    /**
     * Adds coins the server has just confirmed, before the next read of the
     * balance arrives. Called before a claim stops counting as pending, so
     * the number never dips between "owed" and "held".
     */
    fun credit(coins: Int) {
        val held = _balance.value
        if (held is Balance.Known && coins > 0) _balance.value = Balance.Known(held.coins + coins)
    }

    /** For a screen that wants to try again after a failure. */
    fun retryBalance() {
        scope.launch { refreshBalance() }
    }
}
