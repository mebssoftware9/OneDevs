package com.devbangs.onedevs.data.listings

import android.content.Context
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import com.devbangs.onedevs.data.backend.PlatformStats
import com.devbangs.onedevs.data.backend.quietly
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Everything the Board shows, and when it was read. */
data class HomeBoard(
    val stats: PlatformStats?,
    val testing: List<Listing>,
    val live: List<Listing>,
    val fetchedAt: Long,
)

/**
 * The Board's data, kept between visits and between launches.
 *
 * It used to be three requests in a row every time the Board came back into
 * view -- and a tester comes back into view every time they return from the
 * Play Store, which is the whole point of the app. Now:
 *
 *  - one request (home_board) instead of three, or three in parallel against
 *    a database that has not been migrated yet;
 *  - the last Board is on disk, so a cold start shows it at once and the
 *    fetch happens behind it;
 *  - a Board read in the last minute is not read again on resume;
 *  - a failed read keeps what is on screen instead of emptying it;
 *  - one read at a time, however many screens ask.
 *
 * Anything that changes what the Board should show -- the developer's own
 * listings, a test being paid -- marks it stale, so the next visit re-reads.
 */
class BoardStore(
    context: Context,
    private val backend: Backend,
    private val listings: RemoteListingRepository,
    private val account: AccountState,
    scope: CoroutineScope,
    changes: List<kotlinx.coroutines.flow.Flow<*>>,
) {
    private val file = File(context.filesDir, "board_cache.json")
    private val lock = Mutex()

    private val _board = MutableStateFlow<HomeBoard?>(null)
    val board: StateFlow<HomeBoard?> = _board.asStateFlow()

    /** Whether the last attempt failed, for a screen with nothing to show. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    @Volatile private var loadedFor: String? = null

    init {
        scope.launch {
            // Whose Board it is matters: it hides apps *you* have tested. A
            // different account starts from nothing rather than from theirs.
            account.session.collect { session ->
                val me = session?.userId
                if (me != loadedFor) {
                    loadedFor = me
                    _board.value = if (me == null) null else readDisk(me)
                }
            }
        }
        scope.launch {
            merge(*changes.toTypedArray()).drop(1).collect { invalidate() }
        }
    }

    /** The next [refresh] reads, however recent the Board is. */
    fun invalidate() {
        _board.value?.let { _board.value = it.copy(fetchedAt = 0L) }
    }

    /**
     * Reads the Board unless it was read within [maxAgeMs]. Quiet when there
     * is already something on screen: a refresh behind content never shows
     * "Slow connection".
     */
    suspend fun refresh(device: String, maxAgeMs: Long = FRESH_MS) {
        val me = account.session.value?.userId ?: return
        lock.withLock {
            val current = _board.value
            if (current != null && System.currentTimeMillis() - current.fetchedAt < maxAgeMs) return
            val fresh = quietly(quiet = current != null) { fetch(device) }
            if (fresh == null) {
                _failed.value = current == null
                return
            }
            _failed.value = false
            _board.value = fresh.first
            fresh.second?.let { raw -> writeDisk(me, raw) }
        }
    }

    /** The Board, and the raw JSON worth caching when it came in one piece. */
    private suspend fun fetch(device: String): Pair<HomeBoard, String?>? {
        val result = backend.rpc(
            "home_board",
            buildJsonObject {
                put("p_device", JsonPrimitive(device))
                put("p_limit", JsonPrimitive(LIMIT))
            },
        )
        if (result.ok) {
            decode(result.body, System.currentTimeMillis())?.let { return it to result.body }
        }
        // 404: a database without home_board yet. Anything else that is not
        // "unreachable" is a server problem worth the same fallback. Either
        // way, the three reads happen together instead of one after another.
        if (result.offline) return null
        return coroutineScope {
            val stats = async { backend.platformStats() }
            val testing = async { listings.boardOrNull(Channel.Testing, device, LIMIT) }
            val live = async { listings.boardOrNull(Channel.Live, device, LIMIT) }
            val t = testing.await() ?: return@coroutineScope null
            val l = live.await() ?: return@coroutineScope null
            HomeBoard(stats.await(), t, l, System.currentTimeMillis()) to null
        }
    }

    private fun decode(body: String, at: Long): HomeBoard? = try {
        val obj: JsonObject = BackendJson.parseToJsonElement(body).jsonObject
        val stats = obj["stats"]?.let {
            runCatching { BackendJson.decodeFromJsonElement(PlatformStats.serializer(), it) }.getOrNull()
        }
        val testing = obj["testing"]?.let { listings.decodeOrNull(it.toString()) }
        val live = obj["live"]?.let { listings.decodeOrNull(it.toString()) }
        if (testing == null || live == null) null else HomeBoard(stats, testing, live, at)
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // ------------------------------------------------------------------ disk

    private suspend fun readDisk(me: String): HomeBoard? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching null
            val text = file.readText()
            val owner = text.substringBefore('\n')
            val rest = text.substringAfter('\n')
            val at = rest.substringBefore('\n').toLongOrNull() ?: 0L
            // Shown at once, but always refreshed: fetchedAt 0 on a cached copy.
            if (owner != me) null else decode(rest.substringAfter('\n'), at)?.copy(fetchedAt = 0L)
        }.getOrNull()
    }

    private suspend fun writeDisk(me: String, raw: String) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "board_cache.tmp")
            tmp.writeText("$me\n${System.currentTimeMillis()}\n$raw")
            tmp.renameTo(file)
        }
    }

    companion object {
        /** A Board this recent is not re-read on resume. */
        const val FRESH_MS = 60_000L
        const val LIMIT = 50
    }
}
