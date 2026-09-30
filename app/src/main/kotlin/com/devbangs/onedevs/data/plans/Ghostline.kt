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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One phone the app was used on. Never who: the dashboard does not name testers. */
@Serializable
data class GhostInstall(
    val at: String = "",
    val model: String? = null,
    val sdk: Int? = null,
)

@Serializable
data class GhostDay(val day: String = "", val testers: Int = 0)

@Serializable
data class InsightPhone(val model: String = "", val n: Int = 0)

@Serializable
data class InsightAndroid(val sdk: Int = 0, val n: Int = 0)

/**
 * A cycle's report on the four days ending on [day]. Counts and phones only:
 * the server never names a tester to the owner.
 */
@Serializable
data class Insight(
    val day: Int = 0,
    val at: String = "",
    /** Verified testers from the start of the cycle to the end of these four days. */
    val testers: Int = 0,
    /** Of those, how many first used the app in these four days. */
    @SerialName("new") val newTesters: Int = 0,
    /** Average time each tester has spent in the app so far. */
    @SerialName("avg_seconds") val avgSeconds: Int = 0,
    /** Testers who used the app on one day only. */
    @SerialName("one_day") val oneDay: Int = 0,
    val models: List<InsightPhone> = emptyList(),
    val android: List<InsightAndroid> = emptyList(),
)

/** A Ghostline run or a testing cycle, as its owner sees it. */
@Serializable
data class GhostRun(
    val id: String,
    /** "cycle" for a Premium or Pro testing cycle, "ghostline" for a paid run. */
    val kind: String = "ghostline",
    val plan: String? = null,
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
    /** Whether the app heads the Testing Board right now. */
    val spotlight: Boolean = false,
    @SerialName("spotlight_until") val spotlightUntil: String? = null,
    @SerialName("next_spotlight_at") val nextSpotlightAt: String? = null,
    /** Newest first. */
    val insights: List<Insight> = emptyList(),
) {
    val live: Boolean get() = state == "running" || state == "extended"

    val isCycle: Boolean get() = kind == "cycle"

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

/** What the account's plan lets it start this paid month. */
@Serializable
data class CycleAllowance(
    val plan: String = "free",
    /** Cycles each paid month allows. */
    val apps: Int = 0,
    /** Cycles started this paid month. */
    val used: Int = 0,
    /** Testers each cycle aims for; null on Community. */
    val needed: Int? = null,
    /** When the next paid month starts. */
    @SerialName("next_at") val nextAt: String? = null,
) {
    val left: Int get() = (apps - used).coerceAtLeast(0)
}

/** How asking to start a cycle ended. */
sealed interface CycleStart {
    data object Started : CycleStart
    data class Refused(val reason: String, val nextAt: String? = null) : CycleStart
    data object Unreachable : CycleStart
}

@Serializable
private data class StartReply(
    val ok: Boolean = false,
    val reason: String? = null,
    @SerialName("next_at") val nextAt: String? = null,
)

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

    /** What this month still allows; null if unreadable. */
    suspend fun allowance(): CycleAllowance? {
        val result = backend.rpc("cycle_allowance", JsonObject(emptyMap()))
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString(CycleAllowance.serializer(), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Starts a testing cycle for one of this account's apps in testing. */
    suspend fun startCycle(listingId: String): CycleStart {
        val result = backend.rpc(
            "cycle_start",
            buildJsonObject { put("p_listing", JsonPrimitive(listingId)) },
        )
        if (!result.ok) return CycleStart.Unreachable
        val reply = try {
            BackendJson.decodeFromString(StartReply.serializer(), result.body)
        } catch (e: SerializationException) {
            return CycleStart.Unreachable
        } catch (e: IllegalArgumentException) {
            return CycleStart.Unreachable
        }
        return if (reply.ok) CycleStart.Started else CycleStart.Refused(reply.reason ?: "refused", reply.nextAt)
    }
}
