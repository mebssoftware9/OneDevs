package com.devbangs.onedevs.data.claims

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A test that has been earned and not yet acknowledged by the server.
 *
 * The seconds are the measurement, taken before any of this: they are what the
 * tester actually did, and they do not change because a socket did.
 */
@Serializable
data class PendingClaim(
    val id: String = UUID.randomUUID().toString(),
    val account: String,
    val listingId: String,
    val title: String,
    val seconds: Int,
    val device: String,
    val coins: Int,
    val at: Long,
)

/** How a claim ended, once the server finally said something. */
data class Settlement(val claim: PendingClaim, val paid: Boolean, val reason: String?)

/**
 * Claims that are owed, kept on disk until the server says otherwise.
 *
 * The tap that finishes a test does not talk to the network. It writes here and
 * returns, and something else carries the claim the rest of the way. That is
 * the whole point: on a connection that comes and goes, the moment a person
 * presses a button is the worst possible moment to require a round trip, and
 * making them wait for one is how a platform teaches people it cannot be
 * trusted with work they have already done.
 *
 * Writes go to a temporary file and are renamed into place, so a process killed
 * mid-write leaves either the old list or the new one, never half of either.
 */
class ClaimOutbox(context: Context) {

    private val file = File(context.filesDir, "claims.json")
    private val staging = File(context.filesDir, "claims.json.tmp")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _waiting = MutableStateFlow<List<PendingClaim>>(emptyList())
    private val _settled = MutableSharedFlow<Settlement>(extraBufferCapacity = 8)

    /** What is still owed, for a screen that wants to say so. */
    val waiting: StateFlow<List<PendingClaim>> = _waiting.asStateFlow()

    /** Answers as they arrive, for whoever is on screen when they do. */
    val settled: SharedFlow<Settlement> = _settled.asSharedFlow()

    /** Reads what survived the last process. Safe to call more than once. */
    suspend fun load() {
        lock.withLock { _waiting.value = readAll() }
    }

    /**
     * Records a claim. One per listing: pressing twice is the same debt, and a
     * second row would ask the server the same question for no reason.
     */
    suspend fun add(claim: PendingClaim) {
        lock.withLock {
            write(
                readAll().filterNot {
                    it.account == claim.account && it.listingId == claim.listingId
                } + claim,
            )
        }
    }

    /** Drops a claim the server has answered, whichever way it answered. */
    suspend fun settle(claim: PendingClaim, paid: Boolean, reason: String?) {
        lock.withLock { write(readAll().filterNot { it.id == claim.id }) }
        _settled.emit(Settlement(claim, paid, reason))
    }

    private suspend fun readAll(): List<PendingClaim> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<PendingClaim>>(file.readText())
        } catch (e: Exception) {
            // A file we cannot read is a file we cannot honour. Losing it is
            // bad; looping forever on it is worse, and the ledger on the
            // server is the record that actually matters.
            emptyList()
        }
    }

    private suspend fun write(list: List<PendingClaim>) {
        withContext(Dispatchers.IO) {
            staging.writeText(json.encodeToString(list))
            if (!staging.renameTo(file)) file.writeText(staging.readText())
        }
        _waiting.value = list
    }
}
