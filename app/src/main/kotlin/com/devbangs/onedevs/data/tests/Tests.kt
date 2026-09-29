package com.devbangs.onedevs.data.tests

import com.devbangs.onedevs.R
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.Reply
import com.devbangs.onedevs.data.backend.classify
import com.devbangs.onedevs.data.claims.ClaimRecord
import com.devbangs.onedevs.data.claims.ClaimStatus
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Thirty-two seconds, matching the rule the database enforces. */
const val REQUIRED_SECONDS = 32

/**
 * Where a tester stands with a listing, as test_status reports it.
 *
 * [state] is one of: available, open, paid, device_used, device_in_use,
 * unfunded, own_app, missing, signed_out.
 */
@Serializable
data class TestStatus(
    val state: String,
    val reward: Int = 0,
    val session: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** What begin_test made of "Test now". */
@Serializable
data class BeginResult(
    val ok: Boolean = false,
    val reason: String? = null,
    val session: String? = null,
    val reward: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** What finish_test, or the older claim_test, made of a finished test. */
@Serializable
data class FinishResult(
    val paid: Boolean = false,
    val claimed: Boolean = false,
    val coins: Int = 0,
    val reason: String? = null,
    val repeat: Boolean = false,
) {
    /** finish_test says paid; the older claim_test says claimed. */
    val succeeded: Boolean get() = paid || claimed
}

private val TestJson = Json { ignoreUnknownKeys = true }

/**
 * The three questions a test asks the server, each answered as a [Reply] so
 * the caller can tell "no" from "no signal".
 */
class TestRepository(private val backend: Backend) {

    suspend fun status(listingId: String, device: String): Reply<TestStatus> = call(
        "test_status",
        TestStatus.serializer(),
        "p_listing" to listingId,
        "p_device" to device,
    )

    /** Starts a test and reserves its reward, or says why it cannot. */
    suspend fun begin(listingId: String, device: String): Reply<BeginResult> = call(
        "begin_test",
        BeginResult.serializer(),
        "p_listing" to listingId,
        "p_device" to device,
    )

    /** Pays a finished test from its reservation. Safe to ask any number of times. */
    suspend fun finish(sessionId: String, seconds: Int): Reply<FinishResult> = call(
        "finish_test",
        FinishResult.serializer(),
        "p_session" to sessionId,
        "p_seconds" to seconds,
    )

    /** The one-step claim, for claims written before sessions existed. */
    suspend fun claimLegacy(listingId: String, seconds: Int, device: String): Reply<FinishResult> = call(
        "claim_test",
        FinishResult.serializer(),
        "p_listing" to listingId,
        "p_seconds" to seconds,
        "p_device" to device,
    )

    private suspend fun <T> call(
        function: String,
        serializer: KSerializer<T>,
        vararg args: Pair<String, Any>,
    ): Reply<T> {
        val body = buildJsonObject {
            args.forEach { (name, value) ->
                put(name, if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString()))
            }
        }
        return decode(classify(backend.rpc(function, body)), serializer)
    }
}

/**
 * Reads an answer's body. A body that cannot be read is a rejection, not a
 * network problem: asking again returns the same unreadable thing.
 */
internal fun <T> decode(reply: Reply<String>, serializer: KSerializer<T>): Reply<T> = when (reply) {
    is Reply.Answer -> try {
        Reply.Answer(TestJson.decodeFromString(serializer, reply.value))
    } catch (e: SerializationException) {
        Reply.Rejected(-1, "unreadable reply")
    } catch (e: IllegalArgumentException) {
        Reply.Rejected(-1, "unreadable reply")
    }
    is Reply.Unreachable -> reply
    is Reply.SignedOut -> reply
    is Reply.Rejected -> reply
}

/** Postgres timestamps as epoch millis, or null if there is none. */
internal fun epochMillis(timestamp: String?): Long? = try {
    timestamp?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
} catch (e: DateTimeParseException) {
    null
}

/** What the test screen shows, before anything the person is doing right now. */
enum class TestStage {
    /** Asking the server where things stand. */
    Checking,

    /** Nothing in the way. "Test now" will reserve the reward. */
    Ready,

    /** A reward is reserved and the clock is running. */
    Testing,

    /** Finished, and waiting for the server to confirm the payment. */
    Confirming,

    /** Paid. Nothing more to do. */
    Paid,

    /** Something the tester cannot change stands in the way. [TestView.reason] says what. */
    Blocked,
}

data class TestView(
    val stage: TestStage,
    val reason: String? = null,
    val session: String? = null,
    val startedAt: Long? = null,
    val coins: Int = 0,
)

/**
 * Decides what the test screen shows from the two records that exist: the
 * claim this phone holds, and what the server says.
 *
 * Pure, and the order is the point. A claim waiting to be confirmed outranks
 * everything, because the tester has done the work and must see that it is
 * owed. A payment either side knows about comes next. After that the server
 * is the authority; the local refusal is only used when the server could not
 * be asked.
 */
fun decide(local: ClaimRecord?, remote: TestStatus?, loading: Boolean): TestView {
    if (local?.status == ClaimStatus.Pending) {
        return TestView(TestStage.Confirming, session = local.session, coins = local.coins)
    }
    if (local?.status == ClaimStatus.Paid) return TestView(TestStage.Paid, coins = local.coins)
    if (remote != null) {
        return when (remote.state) {
            "paid" -> TestView(TestStage.Paid, coins = remote.reward)
            "open" -> TestView(
                TestStage.Testing,
                session = remote.session,
                startedAt = epochMillis(remote.startedAt),
                coins = remote.reward,
            )
            "available" -> TestView(TestStage.Ready, coins = remote.reward)
            else -> TestView(TestStage.Blocked, reason = remote.state, coins = remote.reward)
        }
    }
    if (loading) return TestView(TestStage.Checking)
    if (local?.status == ClaimStatus.Refused && local.reason != "too_short") {
        return TestView(TestStage.Blocked, reason = local.reason, coins = local.coins)
    }
    return TestView(TestStage.Ready)
}

/**
 * The sentence for a reason the server gave, in the reader's language.
 * Every reason the database can return has one; anything new reads as a
 * plain refusal rather than as nothing.
 */
fun reasonText(reason: String?): Int = when (reason) {
    "already_paid", "paid" -> R.string.test_already
    "device_used" -> R.string.test_device_used
    "device_in_use" -> R.string.test_device_in_use
    "unfunded" -> R.string.test_unfunded
    "too_short" -> R.string.test_too_short_claim
    "expired" -> R.string.test_expired
    "own_app" -> R.string.test_own
    "missing" -> R.string.test_missing
    "signed_out" -> R.string.test_signed_out
    else -> R.string.test_refused
}
