package com.devbangs.onedevs.data.backend

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
internal data class BalanceRow(val balance: Int)

/** How a listing is doing, for the developer who owns it. */
@kotlinx.serialization.Serializable
data class ListingStats(
    val testers: Int = 0,
    val spent: Int = 0,
    @kotlinx.serialization.SerialName("tests_left") val testsLeft: Int = 0,
)

/** One hour's observation, as the pulse table recorded it. */
@kotlinx.serialization.Serializable
data class PulsePoint(val at: String = "", val testers: Int = 0)

/** What the board's hero says, counted by the server rather than invented. */
@kotlinx.serialization.Serializable
data class PlatformStats(
    /** Developers who had OneDevs open in the last fifteen minutes. */
    @kotlinx.serialization.SerialName("live_now") val liveNow: Int = 0,
    @kotlinx.serialization.SerialName("active_24h") val activeTesters: Int = 0,
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

/**
 * Google Play's word that this is the real app on a genuine phone. The
 * server, not this interface, decides what an unverified session may do.
 */
interface Attestor {
    /** Gets Google Play ready to answer quickly; harmless to call again. */
    suspend fun prepare()

    /** An integrity token bound to [requestHash], or null if Google Play cannot give one. */
    suspend fun token(requestHash: String): String?
}

/** SHA-256 of a string as lowercase hex: how a session's access token is named to Google. */
internal fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

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
    private val attestor: Attestor? = null,
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

    /** One proof at a time, and which access token each kind of proof was last for. */
    private val attesting = Mutex()
    @Volatile private var attestedFor: String? = null
    @Volatile private var retriedFor: String? = null

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    /**
     * Opens the connection before anything needs it. The DNS lookup, TCP and
     * TLS handshake are most of a first request's time on a far-away phone
     * network; paying them while the splash screen is up means the Board's
     * request finds a warm connection in the pool. Quiet, and the answer is
     * ignored: it only has to reach the server.
     */
    suspend fun warmUp() {
        if (!configured) return
        quietly { httpRequest(url = "$url/auth/v1/health", headers = mapOf("apikey" to key)) }
        attestor?.prepare()
    }

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
                "Authorization" to "Bearer ${session.accessToken}",
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

    /** Testers so far and DevCoins spent, for a listing you own. */
    suspend fun listingStats(listingId: String): ListingStats? {
        val result = rpc(
            "listing_stats",
            kotlinx.serialization.json.buildJsonObject {
                put("p_listing", kotlinx.serialization.json.JsonPrimitive(listingId))
            },
        )
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString<ListingStats>(result.body)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /**
     * Deletes the signed-in account and everything held about it, on the
     * server; see supabase/functions/delete-account. The local session goes
     * only once the server confirms, so a failure leaves nothing half done.
     */
    suspend fun deleteAccount(): Boolean {
        val result = function("delete-account", JsonObject(emptyMap()))
        if (result.ok) sessions.save(null)
        return result.ok
    }

    /**
     * An Edge Function, signed in as the current user. Purchases are verified
     * here: the database will only grant a plan when a function running with
     * the service role asks it to, after Google has confirmed the payment.
     */
    internal suspend fun function(name: String, args: JsonObject): HttpResult = authorised(
        path = "/functions/v1/$name",
        method = "POST",
        body = args.toString(),
        extra = emptyMap(),
    )

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
            session = (renew(session) as? Renewal.Renewed)?.session ?: session
        }
        attestOnce(session)

        val first = send(path, method, body, session, extra)
        if (first.code != 401) return throughGate(path, method, body, session, extra, first)

        return when (val renewal = renew(session)) {
            is Renewal.Renewed -> {
                attestOnce(renewal.session)
                val second = send(path, method, body, renewal.session, extra)
                throughGate(path, method, body, renewal.session, extra, second)
            }
            // The auth server itself said no: the refresh token is spent, and
            // signed out is the honest state.
            Renewal.Refused -> {
                sessions.save(null)
                first
            }
            // Could not ask. Signing someone out because a tunnel ate the
            // refresh would lose their session to a bad signal; keep it, and
            // report the request as unreachable so it is tried again.
            Renewal.Unreachable -> HttpResult(0, "could not refresh the session")
        }
    }

    /**
     * Proves this session to the server, once per access token: Google Play
     * vouches for the app and the phone, bound to the hash of this exact
     * token, and the attest function records the verdict against it.
     *
     * Once per token whatever the answer, so a build Google will not vouch
     * for asks once an hour, not on every call.
     */
    private suspend fun attestOnce(session: Session) {
        if (attestor == null) return
        val hash = sha256Hex(session.accessToken)
        if (attestedFor == hash) return
        attesting.withLock {
            if (attestedFor != hash) {
                attestedFor = hash
                attestNow(session, hash)
            }
        }
    }

    /**
     * The gate refused a request because this session has no verdict: prove
     * it once more and ask once more. Once per token, so a session Google
     * will not vouch for is told no without the app asking forever.
     */
    private suspend fun throughGate(
        path: String,
        method: String,
        body: String?,
        session: Session,
        extra: Map<String, String>,
        result: HttpResult,
    ): HttpResult {
        if (attestor == null || result.code != 403 || !result.body.contains("\"unverified\"")) return result
        val hash = sha256Hex(session.accessToken)
        val verified = attesting.withLock {
            if (retriedFor == hash) return@withLock false
            retriedFor = hash
            attestedFor = hash
            attestNow(session, hash)
        }
        return if (verified) send(path, method, body, session, extra) else result
    }

    /** Asks Google Play for a token and the attest function for a verdict. True when verified. */
    private suspend fun attestNow(session: Session, hash: String): Boolean {
        val token = attestor?.token(hash) ?: return false
        val result = sendNow(
            path = "/functions/v1/attest",
            method = "POST",
            body = kotlinx.serialization.json.buildJsonObject { put("token", JsonPrimitive(token)) }.toString(),
            session = session,
            extra = emptyMap(),
        )
        if (!result.ok) return false
        return try {
            BackendJson.parseToJsonElement(result.body).jsonObject["verified"]?.jsonPrimitive?.booleanOrNull == true
        } catch (e: kotlinx.serialization.SerializationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private sealed interface Renewal {
        data class Renewed(val session: Session) : Renewal
        data object Refused : Renewal
        data object Unreachable : Renewal
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
        if (System.currentTimeMillis() - started > SlowRequestMs && !result.offline &&
            kotlin.coroutines.coroutineContext[QuietRequest] == null
        ) {
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

    private suspend fun renew(stale: Session): Renewal = refreshing.withLock {
        // Another call may have renewed while this one waited for the lock.
        val latest = sessions.current()
        if (latest != null && latest.accessToken != stale.accessToken) {
            return@withLock Renewal.Renewed(latest)
        }

        val result = httpRequest(
            url = "$url/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            headers = mapOf("apikey" to key),
            body = """{"refresh_token":${JsonPrimitive(stale.refreshToken)}}""",
        )
        // Offline is not "signed out". Keeping the session lets the next
        // attempt, on a better connection, carry on where this left off.
        when (classify(result)) {
            is Reply.Answer -> Unit
            is Reply.Unreachable -> return@withLock Renewal.Unreachable
            else -> return@withLock Renewal.Refused
        }

        val session = try {
            BackendJson.decodeFromString<TokenResponse>(result.body)
                // A refresh response carries no user object, so the id we
                // already know is the only source for it.
                .toSession(System.currentTimeMillis(), fallbackUserId = stale.userId)
                // Same trap as the user id: a refresh response knows neither,
                // so anything not in the token has to be carried across by hand.
                ?.copy(displayName = stale.displayName, photoUrl = stale.photoUrl)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (session == null) return@withLock Renewal.Unreachable
        sessions.save(session)
        Renewal.Renewed(session)
    }
}
