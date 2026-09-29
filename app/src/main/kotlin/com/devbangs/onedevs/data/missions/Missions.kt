package com.devbangs.onedevs.data.missions

import com.devbangs.onedevs.data.backend.Backend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The numbers a mission runs on, in one place because every one of them is a
 * product decision and none of them should be typed into copy twice. The
 * server holds its own copy of the ones it enforces; these are for saying so.
 */
object MissionRules {
    /**
     * Google requires twelve testers opted in continuously for fourteen days
     * for affected personal developer accounts. A group of sixteen means every
     * member has fifteen others testing theirs, which leaves three dropouts of
     * margin before anyone falls under the line.
     */
    const val SLOTS = 16
    const val WINDOW_DAYS = 14
    const val TESTERS_REQUIRED = 12

    /** What a seat costs. Paid once, when the seat is taken. */
    const val ENTRY_FEE = 100

    /** Tasks a member is asked to complete each day, across the whole group. */
    const val DAILY_TASKS = 3

    /**
     * The share of daily tasks a member has to complete to stay in good
     * standing, and how many days they can fall under it before their app is
     * pulled from the group.
     *
     * This counts completed tasks and submitted feedback, never app opens.
     * Opens are the metric that is easiest to hit and means least.
     */
    const val DAILY_THRESHOLD = 0.7f
    const val GRACE_DAYS = 3
}

/** Where a mission is in its life. */
enum class MissionStage {
    /** Still filling. Nothing starts until every slot is taken. */
    Recruiting,

    /** Full, running, counting days. */
    Running,

    /** The window has elapsed. Not "approved" -- see [Mission]. */
    Elapsed,
}

/** One taken seat: whose app sits in it. */
@Serializable
data class MissionSeat(
    val seat: Int,
    val listing: String = "",
    val title: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    val mine: Boolean = false,
)

/**
 * A group of developers testing each other's apps for the window.
 *
 * [day] counts what OneDevs has observed and nothing else. Google decides a
 * closed test on its own record of who was opted in and for how long, and this
 * is not that record. At the end the stage is [MissionStage.Elapsed], never
 * approved, because OneDevs is not in a position to know.
 */
@Serializable
data class Mission(
    val id: String,
    val name: String,
    val slots: Int = MissionRules.SLOTS,
    @SerialName("entry_fee") val entryFee: Int = MissionRules.ENTRY_FEE,
    @SerialName("window_days") val windowDays: Int = MissionRules.WINDOW_DAYS,
    val state: String = "recruiting",
    val day: Int = 0,
    val member: Boolean = false,
    val seats: List<MissionSeat> = emptyList(),
) {
    val stage: MissionStage
        get() = when (state) {
            "running" -> MissionStage.Running
            "elapsed" -> MissionStage.Elapsed
            else -> MissionStage.Recruiting
        }

    val joined: Int get() = seats.size

    /** Free slots. Zero means the next join starts the clock. */
    val open: Int get() = (slots - joined).coerceAtLeast(0)

    /**
     * Testers this member would have if the group holds: everyone else in it.
     * Stated as a fact about the group, not as a prediction about Google.
     */
    val coTesters: Int get() = (joined - 1).coerceAtLeast(0)

    val meetsRequirement: Boolean get() = coTesters >= MissionRules.TESTERS_REQUIRED
}

/** What the server made of a join. A refusal is an answer, not a fault. */
@Serializable
data class JoinResult(
    val joined: Boolean = false,
    val reason: String? = null,
    val coins: Int = 0,
    val repeat: Boolean = false,
)

private val MissionJson = Json { ignoreUnknownKeys = true }

/**
 * Missions as the server keeps them.
 *
 * One mission recruits at a time, so there is one to show rather than a list
 * to scroll; what you have joined is the other list. Every rule -- the fee,
 * the one-mission limit, whose app may sit where -- is the database's, which a
 * modified client cannot argue with.
 */
class MissionRepository(private val backend: Backend) {

    /** The mission everyone joining right now joins, or null if unreadable. */
    suspend fun current(): Mission? {
        val result = backend.rpc("current_mission", JsonObject(emptyMap()))
        if (!result.ok) return null
        return decodeOne(result.body)
    }

    /** Every mission this developer holds a seat in, newest first. */
    suspend fun mine(): List<Mission>? {
        val result = backend.rpc("my_missions", JsonObject(emptyMap()))
        if (!result.ok) return null
        return try {
            MissionJson.decodeFromString(ListSerializer(Mission.serializer()), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** One mission by id, from either list. */
    suspend fun find(id: String): Mission? =
        current()?.takeIf { it.id == id } ?: mine()?.firstOrNull { it.id == id }

    /** Takes a seat with one of your testing apps, paying the entry fee. */
    suspend fun join(missionId: String, listingId: String, device: String): JoinResult? {
        val result = backend.rpc(
            "take_seat",
            buildJsonObject {
                put("p_mission", JsonPrimitive(missionId))
                put("p_listing", JsonPrimitive(listingId))
                put("p_device", JsonPrimitive(device))
            },
        )
        if (!result.ok) return null
        return try {
            MissionJson.decodeFromString(JoinResult.serializer(), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeOne(body: String): Mission? = try {
        MissionJson.decodeFromString(Mission.serializer(), body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
