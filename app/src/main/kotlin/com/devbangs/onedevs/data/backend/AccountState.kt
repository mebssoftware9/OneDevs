package com.devbangs.onedevs.data.backend

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Who is signed in, and what they have, held once for the whole app.
 *
 * Both the top bar and the account card need these two facts. Left to load
 * them separately they would disagree -- signing in on Profile would leave the
 * bar showing a stranger's glyph and a zero balance until the next cold start.
 * One holder, two collectors, one truth.
 */
class AccountState(
    private val sessions: SessionHolder,
    private val backend: Backend,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** null while unknown or unreachable; a number only when the server said so. */
    private val _balance = MutableStateFlow<Int?>(null)
    val balance: StateFlow<Int?> = _balance.asStateFlow()

    /**
     * False until the stored session has been looked for.
     *
     * Without this, "no session yet" and "signed out" are the same value, and
     * every cold start shows a signed-in developer the sign-in screen for a
     * frame before correcting itself.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch {
            // A corrupt session file must not leave anyone staring at a splash
            // screen forever. Unreadable means signed out, which is recoverable
            // in one tap; a frozen launch is not recoverable at all.
            _session.value = try {
                sessions.current()
            } catch (e: IOException) {
                null
            }
            _ready.value = true
            refreshBalance()
        }
    }

    private val _loadingBalance = MutableStateFlow(false)
    val loadingBalance: StateFlow<Boolean> = _loadingBalance.asStateFlow()

    /**
     * Asks the server what the balance is, and asks again if it cannot.
     *
     * One attempt was a bug rather than a shortcut: a single timeout on a bad
     * connection left the balance unknown until the app was killed and
     * reopened, which is exactly what happened. Two retries with a short pause
     * covers a dropped request without making a signed-out state wait.
     */
    suspend fun refreshBalance() {
        if (_session.value == null) {
            _balance.value = null
            return
        }
        _loadingBalance.value = true
        var answer: Int? = null
        var attempt = 0
        while (answer == null && attempt < 3) {
            answer = backend.balance()
            if (answer == null) {
                attempt++
                if (attempt < 3) delay(1500L * attempt)
            }
        }
        // Left alone on failure rather than blanked: a balance that was read a
        // minute ago is better company than a dash.
        if (answer != null) _balance.value = answer
        _loadingBalance.value = false
    }

    /**
     * Runs its own work in its own scope, and this is not a style choice.
     *
     * Publishing the session flips the gate, which disposes the sign-in screen,
     * which cancels the scope the caller was using -- so a balance fetched on
     * the caller's coroutine was killed by the state change that started it.
     * That is why the count only ever appeared after a restart.
     */
    fun onSignedIn(session: Session) {
        _session.value = session
        scope.launch { refreshBalance() }
    }

    fun signOut() {
        _session.value = null
        _balance.value = null
        scope.launch { backend.signOut() }
    }
}
