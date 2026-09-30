package com.devbangs.onedevs.data.missions

import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.HttpResult
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

/**
 * One taken seat: whose app sits in it.
 *
 * Everything after [mine] is only sent to members, who need it to do their
 * tasks; anyone browsing the mission sees titles and icons.
 */
@Serializable
data class MissionSeat(
    val seat: Int,
    val listing: String = "",
    val title: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    val mine: Boolean = false,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("play_url") val playUrl: String? = null,
    /** Whether you have used this app today. */
    @SerialName("done_today") val doneToday: Boolean = false,
    /** Whether you have used this app at all during the mission. */
    @SerialName("done_ever") val doneEver: Boolean = false,
    /**
     * How many of the others this seat's member has used: today once the
     * mission runs, at all while it is still filling. Null for non-members.
     */
    val tested: Int? = null,
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
    /** Seats members have taken. Extra apps placed in the mission are not seats. */
    val taken: Int? = null,
    val seats: List<MissionSeat> = emptyList(),
) {
    val stage: MissionStage
        get() = when (state) {
            "running" -> MissionStage.Running
            "elapsed" -> MissionStage.Elapsed
            else -> MissionStage.Recruiting
        }

    val joined: Int get() = taken ?: seats.count { it.seat >= 1 }

    /** Free slots. Zero means the next join starts the clock. */
    val open: Int get() = (slots - joined).coerceAtLeast(0)

    /**
     * Testers this member would have if the group holds: everyone else in it.
     * Stated as a fact about the group, not as a prediction about Google.
     */
    val coTesters: Int get() = (joined - 1).coerceAtLeast(0)

    val meetsRequirement: Boolean get() = coTesters >= MissionRules.TESTERS_REQUIRED

    /** The other members' apps: the tasks. */
    val others: List<MissionSeat> get() = seats.filterNot { it.mine }

    /**
     * Whether a task is done for the current stage: used at all while the
     * mission fills (installing ahead), used today once it runs.
     */
    fun done(seat: MissionSeat): Boolean =
        if (stage == MissionStage.Recruiting) seat.doneEver else seat.doneToday

    val tasksDone: Int get() = others.count { done(it) }
}

/** One line in Mission Command. */
@Serializable
data class MissionMessage(
    val id: Long,
    /** chat, or the server's own: join (body is the app), start (body is the days). */
    val kind: String = "chat",
    val body: String = "",
    val at: String = "",
    val mine: Boolean = false,
    val author: String? = null,
    val seat: Int? = null,
)

/** What the server made of a post or a check-in. */
@Serializable
data class MissionAck(
    val ok: Boolean = false,
    val reason: String? = null,
    /** DevCoins the check-in earned, when it earned any. */
    val coins: Int = 0,
)

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

    /** Records that you used another member's app for [seconds] today. */
    suspend fun checkIn(missionId: String, listingId: String, seconds: Int, device: String): MissionAck? =
        ack(
            backend.rpc(
                "mission_checkin",
                buildJsonObject {
                    put("p_mission", JsonPrimitive(missionId))
                    put("p_listing", JsonPrimitive(listingId))
                    put("p_seconds", JsonPrimitive(seconds))
                    put("p_device", JsonPrimitive(device))
                    put("p_model", JsonPrimitive(phoneModel()))
                    put("p_sdk", JsonPrimitive(android.os.Build.VERSION.SDK_INT))
                },
            ),
        )

    /** Mission Command, oldest first: everything after [after], or the latest page. */
    suspend fun feed(missionId: String, after: Long = 0L): List<MissionMessage>? {
        val result = backend.rpc(
            "mission_feed",
            buildJsonObject {
                put("p_mission", JsonPrimitive(missionId))
                put("p_after", JsonPrimitive(after))
            },
        )
        if (!result.ok) return null
        return try {
            MissionJson.decodeFromString(ListSerializer(MissionMessage.serializer()), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Posts to Mission Command. */
    suspend fun post(missionId: String, body: String): MissionAck? = ack(
        backend.rpc(
            "mission_post",
            buildJsonObject {
                put("p_mission", JsonPrimitive(missionId))
                put("p_body", JsonPrimitive(body))
            },
        ),
    )

    private fun ack(result: HttpResult): MissionAck? {
        if (!result.ok) return null
        return try {
            MissionJson.decodeFromString(MissionAck.serializer(), result.body)
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

/** "Samsung Galaxy S24", not "samsung SM-S921B samsung". */
internal fun phoneModel(
    maker: String = android.os.Build.MANUFACTURER.orEmpty(),
    model: String = android.os.Build.MODEL.orEmpty(),
): String {
    val brand = maker.replaceFirstChar { it.titlecase() }
    return when {
        model.isBlank() -> brand
        model.startsWith(maker, ignoreCase = true) -> model.replaceFirstChar { it.titlecase() }
        else -> "$brand $model"
    }.take(80)
}
