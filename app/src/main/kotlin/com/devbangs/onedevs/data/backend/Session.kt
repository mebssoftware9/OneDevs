package com.devbangs.onedevs.data.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How long before expiry a token is treated as due for renewal. */
internal const val RefreshSkewMs = 60_000L

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis, computed from the server's expires_in and the local clock. */
    val expiresAt: Long,
    val userId: String,
    /**
     * From the Google credential, not the token -- so it survives a refresh
     * only because the refresh carries it forward deliberately. Defaulted so a
     * session stored before this field existed still reads.
     */
    val displayName: String? = null,
    val photoUrl: String? = null,
)

/**
 * Whether to renew before making a call.
 *
 * This is an optimisation, not a correctness rule. Phone clocks are wrong more
 * often than anyone likes, and a wrong clock here means either a needless
 * refresh or a 401 -- both of which the caller already handles by refreshing
 * once and retrying. Nothing is allowed to depend on this being right.
 */
internal fun Session.needsRefresh(now: Long, skewMs: Long = RefreshSkewMs): Boolean =
    now >= expiresAt - skewMs

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600L,
    val user: TokenUser? = null,
)

@Serializable
internal data class TokenUser(val id: String)

/**
 * Expiry is measured from now plus the lifetime the server reported, rather
 * than from the absolute expires_at it also sends. An absolute timestamp is
 * only meaningful if both clocks agree; a duration only needs the clock to
 * keep ticking, which is a far weaker assumption.
 */
internal fun TokenResponse.toSession(now: Long, fallbackUserId: String? = null): Session? {
    val id = user?.id ?: fallbackUserId ?: return null
    return Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = now + expiresIn.coerceAtLeast(0L) * 1000L,
        userId = id,
    )
}
