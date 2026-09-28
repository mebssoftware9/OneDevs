package com.devbangs.onedevs.data.backend

import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** null while unknown or unreachable; a number only when the server said so. */
    private val _balance = MutableStateFlow<Int?>(null)
    val balance: StateFlow<Int?> = _balance.asStateFlow()

    init {
        scope.launch {
            _session.value = sessions.current()
            refreshBalance()
        }
    }

    suspend fun refreshBalance() {
        _balance.value = if (_session.value != null) backend.balance() else null
    }

    suspend fun onSignedIn(session: Session) {
        _session.value = session
        refreshBalance()
    }

    suspend fun signOut() {
        backend.signOut()
        _session.value = null
        _balance.value = null
    }
}
