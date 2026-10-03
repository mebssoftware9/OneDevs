package com.devbangs.onedevs.data.badges

import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** One badge as the server worked it out for this account. */
@Serializable
data class BadgeState(
    val key: String,
    val earned: Boolean = false,
    /** Progress toward it, in whatever the badge counts. */
    val have: Int = 0,
    val need: Int = 1,
)

/** Badges are earned on the server, from what the account actually did. */
class BadgeRepository(private val backend: Backend) {
    /** Every badge with its state, or null if the server could not be read. */
    suspend fun mine(): List<BadgeState>? {
        val result = backend.rpc("my_badges", JsonObject(emptyMap()))
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString(ListSerializer(BadgeState.serializer()), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
