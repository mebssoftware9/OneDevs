package com.devbangs.onedevs.data.backend

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@kotlinx.serialization.Serializable
internal data class BalanceRow(val balance: Int)

/** What the server made of a claim. */
@kotlinx.serialization.Serializable
data class ClaimResult(
    val claimed: Boolean = false,
    val coins: Int = 0,
    val reason: String? = null,
)

/** One hour's observation, as the pulse table recorded it. */
@kotlinx.serialization.Serializable
data class PulsePoint(val at: String = "", val testers: Int = 0)

/** What the board's hero says, counted by the server rather than invented. */
@kotlinx.serialization.Serializable
data class PlatformStats(
    @kotlinx.serialization.SerialName("testers_active_24h") val activeTesters: Int = 0,
    @kotlinx.serialization.SerialName("apps_in_testing") val appsInTesting: Int = 0,
    @kotlinx.serialization.SerialName("open_missions") val openMissions: Int = 0,
    val pulse: List<PulsePoint> = emptyList(),
)

/** Long enough to be a wait rather than a network. */
private const val SlowRequestMs = 3_500L

internal val BackendJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Where the signed-in session lives between calls. */
interface SessionHolder {
    suspend fun current(): Session?
    suspend fun save(session: Session?)
}

/**
 * The whole backend surface: sign in, read tables, call functions.
 *
 * It stays this small on purpose. Every rule that matters -- what a mission
 * costs, when a day counts, who may be paid -- lives in the database, where a
 * modified client cannot argue with it. This class carries bytes and a token.
 */
class Backend(
    private val url: String,
    private val key: String,
    private val sessions: SessionHolder,
) {
    /**
     * Emitted when a request took long enough that someone noticed. Measured
     * from what actually happened rather than from the system's opinion of the
     * connection, which is the only version that matches the wait.
     */
    private val _slow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val slow: SharedFlow<Unit> = _slow.asSharedFlow()

    /** One refresh at a time, however many calls discover the token is stale. */
    private val refreshing = Mutex()

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    suspend fun signInWithGoogle(
        idToken: String,
        nonce: String? = null,
        displayName: String? = null,
        photoUrl: String? = null,
    ): Session? {
        val payload = buildString {
            append("""{"provider":"google","id_token":""")
            append(JsonPrimitive(idToken))
            if (nonce != null) {
                append(""","nonce":""")
                append(JsonPrimitive(nonce))
            }
            append("}")
        }
        val result = httpRequest(
            url = "$url/auth/v1/token?grant_type=id_token",
            method = "POST",
            headers = mapOf("apikey" to key),
            body = payload,
        )
        if (!result.ok) return null
        val session = BackendJson.decodeFromString<TokenResponse>(result.body)
            .toSession(System.currentTimeMillis())
            ?.copy(displayName = displayName, photoUrl = photoUrl)
        if (session != null) sessions.save(session)
        return session
    }

    suspend fun signOut() {
        val session = sessions.current()
        sessions.save(null)
        if (session != null) {
            // Best effort. The local session is already gone either way; a
            // failed round trip must not leave someone apparently signed in.
            httpRequest(
                url = "$url/auth/v1/logout",
                method = "POST",
                headers = mapOf(
                    "apikey" to key,
                    "Authorization" to "Bearer ${session.accessToken}",
                ),
            )
        }
    }

    /** A PostgREST call: "listings?select=*", "listings?id=eq.$id", and so on. */
    internal suspend fun rest(
        path: String,
        method: String = "GET",
        body: String? = null,
        prefer: String? = null,
    ): HttpResult = authorised(
        path = "/rest/v1/$path",
        method = method,
        body = body,
        extra = if (prefer != null) mapOf("Prefer" to prefer) else emptyMap(),
    )

    /**
     * The signed-in developer's DevCoins, as the server counts them.
     *
     * null means the question could not be answered -- offline, or refused --
     * which the card shows as a dash. No row means zero, which is a real
     * answer and shows as 0.
     */
    suspend fun balance(): Int? {
        val result = rest("coin_balance?select=balance")
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString<List<BalanceRow>>(result.body).firstOrNull()?.balance ?: 0
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /**
     * Puts an app icon in the public bucket and returns the URL everyone else
     * will load it from, or null if it could not be stored.
     *
     * Named by owner and listing, which is also what the storage policy checks:
     * a developer can replace their own icons and nobody else's.
     */
    internal suspend fun uploadIcon(userId: String, listingId: String, png: ByteArray): String? {
        val session = sessions.current() ?: return null
        val path = "$userId/$listingId.png"
        val result = httpUpload(
            url = "$url/storage/v1/object/icons/$path",
            // upsert so re-saving a listing replaces its icon instead of
            // failing on a name that is already taken.
            method = "POST",
            headers = mapOf(
                "apikey" to key,
                "Authorization" to "Bearer ${'$'}{session.accessToken}",
                "x-upsert" to "true",
            ),
            bytes = png,
            contentType = "image/png",
        )
        return if (result.ok) "$url/storage/v1/object/public/icons/$path" else null
    }

    /** The numbers at the top of the Board, or null if they could not be read. */
    suspend fun platformStats(): PlatformStats? {
        val result = rpc("platform_stats", kotlinx.serialization.json.JsonObject(emptyMap()))
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString<PlatformStats>(result.body)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /**
     * Claims the reward for a test.
     *
     * The device says how many seconds it observed; the server decides
     * everything else -- whether the listing can still pay, whether this
     * tester already claimed, whether it is their own app. A refusal comes
     * back as claimed=false with a reason rather than as an error, because
     * "you already claimed this" is an answer, not a fault.
     */
    suspend fun claimTest(listingId: String, seconds: Int, device: String): ClaimResult? {
        val result = rpc(
            "claim_test",
            kotlinx.serialization.json.buildJsonObject {
                put("p_listing", kotlinx.serialization.json.JsonPrimitive(listingId))
                put("p_seconds", kotlinx.serialization.json.JsonPrimitive(seconds))
                put("p_device", kotlinx.serialization.json.JsonPrimitive(device))
            },
        )
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString<ClaimResult>(result.body)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /** A database function. Every coin movement arrives through here. */
    internal suspend fun rpc(function: String, args: JsonObject): HttpResult = authorised(
        path = "/rest/v1/rpc/$function",
        method = "POST",
        body = args.toString(),
        extra = emptyMap(),
    )

    /**
     * Sends with the current token, and treats a 401 as "refresh once, retry
     * once".
     *
     * That rule is what makes the clock unimportant. A phone whose date is
     * wrong -- common enough not to be an edge case -- will either refresh
     * needlessly or be told 401, and both paths end in the same place. Nothing
     * here depends on the device knowing what time it is.
     */
    private suspend fun authorised(
        path: String,
        method: String,
        body: String?,
        extra: Map<String, String>,
    ): HttpResult {
        var session = sessions.current() ?: return HttpResult(401, "not signed in")
        if (session.needsRefresh(System.currentTimeMillis())) {
            session = renew(session) ?: session
        }

        val first = send(path, method, body, session, extra)
        if (first.code != 401) return first

        val renewed = renew(session)
        if (renewed == null) {
            // The refresh token is spent too. Signed out is the honest state.
            sessions.save(null)
            return first
        }
        return send(path, method, body, renewed, extra)
    }

    private suspend fun send(
        path: String,
        method: String,
        body: String?,
        session: Session,
        extra: Map<String, String>,
    ): HttpResult {
        val started = System.currentTimeMillis()
        val result = sendNow(path, method, body, session, extra)
        if (System.currentTimeMillis() - started > SlowRequestMs && !result.offline) {
            _slow.tryEmit(Unit)
        }
        return result
    }

    private suspend fun sendNow(
        path: String,
        method: String,
        body: String?,
        session: Session,
        extra: Map<String, String>,
    ): HttpResult = httpRequest(
        url = url + path,
        method = method,
        headers = buildMap {
            put("apikey", key)
            put("Authorization", "Bearer ${session.accessToken}")
            putAll(extra)
        },
        body = body,
    )

    private suspend fun renew(stale: Session): Session? = refreshing.withLock {
        // Another call may have renewed while this one waited for the lock.
        val latest = sessions.current()
        if (latest != null && latest.accessToken != stale.accessToken) {
            return@withLock latest
        }

        val result = httpRequest(
            url = "$url/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            headers = mapOf("apikey" to key),
            body = """{"refresh_token":${JsonPrimitive(stale.refreshToken)}}""",
        )
        // Offline is not "signed out". Keeping the session lets the next
        // attempt, on a better connection, carry on where this left off.
        if (result.offline || !result.ok) return@withLock null

        val session = BackendJson.decodeFromString<TokenResponse>(result.body)
            // A refresh response carries no user object, so the id we already
            // know is the only source for it.
            .toSession(System.currentTimeMillis(), fallbackUserId = stale.userId)
            // Same trap as the user id: a refresh response knows neither, so
            // anything not in the token has to be carried across by hand.
            ?.copy(displayName = stale.displayName, photoUrl = stale.photoUrl)
        if (session != null) sessions.save(session)
        session
    }
}
