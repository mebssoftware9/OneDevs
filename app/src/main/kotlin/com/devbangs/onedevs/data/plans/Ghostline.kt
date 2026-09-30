package com.devbangs.onedevs.data.plans

import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** One phone the app was used on. Never who: the dashboard does not name testers. */
@Serializable
data class GhostInstall(
    val at: String = "",
    val model: String? = null,
    val sdk: Int? = null,
)

@Serializable
data class GhostDay(val day: String = "", val testers: Int = 0)

/** A Ghostline run as its owner sees it. */
@Serializable
data class GhostRun(
    val id: String,
    val state: String = "running",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("ends_at") val endsAt: String = "",
    @SerialName("server_now") val serverNow: String = "",
    val listing: String = "",
    val title: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("package_name") val packageName: String = "",
    val needed: Int = Products.GHOSTLINE_TESTERS,
    val testers: Int = 0,
    @SerialName("active_today") val activeToday: Int = 0,
    val missions: Int = 0,
    val boosts: Int = 0,
    @SerialName("next_boost_at") val nextBoostAt: String? = null,
    val installs: List<GhostInstall> = emptyList(),
    val daily: List<GhostDay> = emptyList(),
) {
    val live: Boolean get() = state == "running" || state == "extended"

    /** Phones per Android version, most common first. */
    val byAndroid: List<Pair<Int, Int>>
        get() = installs.mapNotNull { it.sdk }.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }
}

/** Epoch millis of a Postgres timestamp, or null. */
fun epochOf(value: String?): Long? = value?.let {
    try {
        OffsetDateTime.parse(it).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

/** The Android release an API level shipped as. */
fun androidName(sdk: Int): String = when (sdk) {
    in 37..99 -> "${sdk - 20}"
    36 -> "16"
    35 -> "15"
    34 -> "14"
    33 -> "13"
    32 -> "12L"
    31 -> "12"
    30 -> "11"
    29 -> "10"
    28 -> "9"
    27 -> "8.1"
    26 -> "8.0"
    25 -> "7.1"
    24 -> "7.0"
    else -> "API $sdk"
}

class GhostlineRepository(private val backend: Backend) {
    /** Every run this account has paid for, newest first; null if unreadable. */
    suspend fun runs(): List<GhostRun>? {
        val result = backend.rpc("ghostline_dashboard", JsonObject(emptyMap()))
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString(ListSerializer(GhostRun.serializer()), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
