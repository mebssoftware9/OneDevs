package com.devbangs.onedevs.data.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /** One refresh at a time, however many calls discover the token is stale. */
    private val refreshing = Mutex()

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    suspend fun signInWithGoogle(idToken: String, nonce: String? = null): Session? {
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
        if (session != null) sessions.save(session)
        session
    }
}
