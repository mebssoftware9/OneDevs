package com.devbangs.onedevs.data.feedback

import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One report about an app: a bug, or a suggestion. */
@Serializable
data class FeedbackReport(
    val id: Long,
    /** "bug" or "suggestion". */
    val kind: String = "suggestion",
    val body: String = "",
    /** "new", "accepted", "confirmed" (a real bug) or "dismissed". */
    val status: String = "new",
    val at: String = "",
    val mine: Boolean = false,
    val author: String? = null,
) {
    val isBug: Boolean get() = kind == "bug"
}

/** What the server made of a report or a review. A refusal is an answer, not a fault. */
@Serializable
data class FeedbackAck(val ok: Boolean = false, val reason: String? = null)

/** The shortest and longest report the server takes. */
object FeedbackRules {
    const val MIN = 20
    const val MAX = 2000
}

/**
 * Reports testers send about apps they used, and what the developer made of
 * them. Who may send, read and mark one is the server's rule, not this class's.
 */
class FeedbackRepository(private val backend: Backend) {

    /** Reports on [listingId]: all of them for its developer, your own for anyone else. */
    suspend fun forListing(listingId: String): List<FeedbackReport>? {
        val result = backend.rpc(
            "feedback_for_listing",
            buildJsonObject { put("p_listing", JsonPrimitive(listingId)) },
        )
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString(ListSerializer(FeedbackReport.serializer()), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    suspend fun submit(listingId: String, bug: Boolean, body: String): FeedbackAck? = ack(
        backend.rpc(
            "feedback_submit",
            buildJsonObject {
                put("p_listing", JsonPrimitive(listingId))
                put("p_kind", JsonPrimitive(if (bug) "bug" else "suggestion"))
                put("p_body", JsonPrimitive(body))
            },
        ),
    )

    /** The developer's verdict: "accepted", "confirmed" or "dismissed". */
    suspend fun review(reportId: Long, status: String): FeedbackAck? = ack(
        backend.rpc(
            "feedback_review",
            buildJsonObject {
                put("p_report", JsonPrimitive(reportId))
                put("p_status", JsonPrimitive(status))
            },
        ),
    )

    private fun ack(result: com.devbangs.onedevs.data.backend.HttpResult): FeedbackAck? {
        if (!result.ok) return null
        return try {
            BackendJson.decodeFromString(FeedbackAck.serializer(), result.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
