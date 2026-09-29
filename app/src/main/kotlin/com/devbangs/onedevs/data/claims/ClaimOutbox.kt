package com.devbangs.onedevs.data.claims

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Where a claim stands. */
@Serializable
enum class ClaimStatus { Pending, Paid, Refused }

/**
 * A finished test and what became of it.
 *
 * Kept after it is settled, not deleted. The old outbox dropped a claim the
 * moment the server answered and announced the answer to whichever screen
 * happened to be listening -- usually none -- so a refusal arrived as coins
 * silently vanishing from the balance. A record that stays says what happened
 * to anyone who looks, whenever they look.
 *
 * The seconds are the measurement, taken before any of this: what the tester
 * actually did, and they do not change because a socket did.
 */
@Serializable
data class ClaimRecord(
    val id: String = UUID.randomUUID().toString(),
    val account: String,
    val listingId: String,
    val title: String,
    val seconds: Int,
    val device: String,
    val coins: Int,
    val at: Long,
    /** The reservation this claim pays from. Null for claims written before sessions. */
    val session: String? = null,
    val status: ClaimStatus = ClaimStatus.Pending,
    val reason: String? = null,
    val settledAt: Long = 0L,
    /** Tries that got no answer. Shown so a long wait is explained, never used to give up. */
    val attempts: Int = 0,
    val lastTriedAt: Long = 0L,
)

/**
 * The rules for the record book, apart from the file that holds it, so they
 * can be tested without a device.
 */
internal object ClaimBook {

    /** Settled records older than this are dropped; the server's ledger is the history. */
    const val KEEP_SETTLED_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Adds a claim, replacing an earlier one for the same account and listing
     * -- unless that one was paid. A paid claim is the last word; nothing on
     * this phone may overwrite it with a question.
     */
    fun add(book: List<ClaimRecord>, claim: ClaimRecord): List<ClaimRecord> {
        val same = book.firstOrNull { it.account == claim.account && it.listingId == claim.listingId }
        if (same?.status == ClaimStatus.Paid) return book
        return book.filterNot { it.account == claim.account && it.listingId == claim.listingId } + claim
    }

    fun settle(book: List<ClaimRecord>, id: String, paid: Boolean, coins: Int?, reason: String?, now: Long) =
        book.map {
            if (it.id != id) {
                it
            } else {
                it.copy(
                    status = if (paid) ClaimStatus.Paid else ClaimStatus.Refused,
                    coins = coins ?: it.coins,
                    reason = if (paid) null else reason,
                    settledAt = now,
                )
            }
        }

    fun attempted(book: List<ClaimRecord>, id: String, now: Long) =
        book.map { if (it.id == id) it.copy(attempts = it.attempts + 1, lastTriedAt = now) else it }

    fun prune(book: List<ClaimRecord>, now: Long) =
        book.filter { it.status == ClaimStatus.Pending || now - it.settledAt < KEEP_SETTLED_MS }

    fun pending(book: List<ClaimRecord>) = book.filter { it.status == ClaimStatus.Pending }

    fun find(book: List<ClaimRecord>, account: String?, listingId: String): ClaimRecord? =
        if (account == null) null else book.lastOrNull { it.account == account && it.listingId == listingId }
}

/**
 * Every claim this phone has made, kept on disk.
 *
 * The tap that finishes a test does not talk to the network. It writes here
 * and returns, and [ClaimWorker] carries the claim the rest of the way. On a
 * connection that comes and goes, the moment a person presses a button is the
 * worst moment to require a round trip.
 *
 * Writes go to a temporary file and are renamed into place, so a process
 * killed mid-write leaves either the old book or the new one, never half.
 */
class ClaimOutbox(context: Context) {

    private val file = File(context.filesDir, "claims.json")
    private val staging = File(context.filesDir, "claims.json.tmp")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _records = MutableStateFlow<List<ClaimRecord>>(emptyList())
    private val _waiting = MutableStateFlow<List<ClaimRecord>>(emptyList())

    /** Everything, settled or not, for the screen that wants to say what happened. */
    val records: StateFlow<List<ClaimRecord>> = _records.asStateFlow()

    /** What is still owed and not yet confirmed. */
    val waiting: StateFlow<List<ClaimRecord>> = _waiting.asStateFlow()

    /** Reads what survived the last process. Safe to call more than once. */
    suspend fun load() {
        lock.withLock { publish(ClaimBook.prune(readAll(), System.currentTimeMillis())) }
    }

    suspend fun add(claim: ClaimRecord) = change { ClaimBook.add(it, claim) }

    suspend fun settle(id: String, paid: Boolean, coins: Int?, reason: String?) =
        change { ClaimBook.settle(it, id, paid, coins, reason, System.currentTimeMillis()) }

    suspend fun attempted(id: String) = change { ClaimBook.attempted(it, id, System.currentTimeMillis()) }

    fun find(account: String?, listingId: String): ClaimRecord? =
        ClaimBook.find(_records.value, account, listingId)

    private suspend fun change(edit: (List<ClaimRecord>) -> List<ClaimRecord>) {
        lock.withLock {
            val next = edit(readAll())
            write(next)
            publish(next)
        }
    }

    private fun publish(book: List<ClaimRecord>) {
        _records.value = book
        _waiting.value = ClaimBook.pending(book)
    }

    private suspend fun readAll(): List<ClaimRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<ClaimRecord>>(file.readText())
        } catch (e: Exception) {
            // A file we cannot read is a file we cannot honour. Losing it is
            // bad; looping forever on it is worse, and the ledger on the
            // server is the record that actually matters.
            emptyList()
        }
    }

    private suspend fun write(list: List<ClaimRecord>) = withContext(Dispatchers.IO) {
        staging.writeText(json.encodeToString(list))
        if (!staging.renameTo(file)) file.writeText(staging.readText())
    }
}
