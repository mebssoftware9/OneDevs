package com.devbangs.onedevs.data.plans

import android.content.Context
import androidx.core.content.edit
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import com.devbangs.onedevs.data.backend.quietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The products as they are named in Play Console. */
object Products {
    /** Subscription with two base plans. */
    const val LAB_PRO = "lab_pro"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"

    /**
     * The paid plans: one monthly subscription each, on base plan [MONTHLY].
     * Community is free and has no product.
     */
    const val PREMIUM = "premium"
    const val PRO = "pro"
    val PLANS = listOf(PREMIUM, PRO)

    /** Consumable one-time product: one Ghostline run per purchase. */
    const val GHOSTLINE = "ghostline"

    /** What a free Lab keeps, and what a run is made of. Stated, not enforced here. */
    const val FREE_APPS = 1
    const val GHOSTLINE_DAYS = 14
    const val GHOSTLINE_TESTERS = 12
    const val GHOSTLINE_BOOST_EVERY = 4
    const val GHOSTLINE_LAB_DAYS = 30

    /** A testing cycle's length before any extension. The server decides; this draws it. */
    const val CYCLE_DAYS = 16
}

/** The three plans, from free to the top. */
enum class Tier { Community, Premium, Pro }

/** What the server says this account has. */
@Serializable
data class Plan(
    val plan: String = "free",
    @SerialName("pro_until") val proUntil: String? = null,
    @SerialName("pro_source") val proSource: String? = null,
    @SerialName("lab_app") val labApp: String? = null,
    /** The apps the Lab keeps, first chosen first. */
    @SerialName("lab_apps") val labApps: List<String> = emptyList(),
    /**
     * How many apps the Lab keeps; null when there is no limit. A reply
     * without the field predates plans, so it gets the smallest Lab.
     */
    @SerialName("lab_limit") val labLimit: Int? = 1,
) {
    val pro: Boolean get() = plan == "pro"

    /** Which of the three plans this is. A name this build does not know is Community. */
    val tier: Tier
        get() = when (plan) {
            "premium" -> Tier.Premium
            "pro" -> Tier.Pro
            else -> Tier.Community
        }

    /** The apps the Lab keeps. A reply from before plans names only the first. */
    val labKept: List<String> get() = labApps.ifEmpty { listOfNotNull(labApp) }

    /** Whether the Lab may take [packageName], as far as this reply knows. */
    fun labAllows(packageName: String): Boolean {
        val limit = labLimit ?: return true
        return packageName in labKept || labKept.size < limit
    }
}

/** Whether the Lab may analyse an app. */
sealed interface LabClaim {
    data object Allowed : LabClaim

    /** The Lab is full: it keeps [kept], which is all [limit] allows. */
    data class Upgrade(val kept: List<String>, val limit: Int) : LabClaim

    /** Could not ask, and nothing on this phone says it is allowed. */
    data object Unknown : LabClaim
}

@Serializable
private data class ClaimReply(
    val ok: Boolean = false,
    val reason: String? = null,
    @SerialName("lab_app") val labApp: String? = null,
    @SerialName("lab_apps") val labApps: List<String> = emptyList(),
    @SerialName("lab_limit") val labLimit: Int? = null,
)

/**
 * The account's plan, kept on the phone as well as read from the server.
 *
 * The server is the only judge -- the Lab claim is checked there, and a plan
 * is only ever granted by a verified purchase -- but the last answer is kept
 * so a Lab opened without signal still knows which app it belongs to.
 */
class PlanStore(
    context: Context,
    private val backend: Backend,
    private val account: AccountState,
    scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("plan", Context.MODE_PRIVATE)

    private val _plan = MutableStateFlow(readCache())
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    init {
        scope.launch {
            account.session.collect { session ->
                if (session == null) {
                    _plan.value = null
                } else if (prefs.getString(OWNER, null) != session.userId) {
                    _plan.value = null
                    refresh()
                } else {
                    refresh()
                }
            }
        }
    }

    suspend fun refresh() {
        val me = account.session.value?.userId ?: return
        val result = quietly { backend.rpc("my_plan", JsonObject(emptyMap())) }
        if (!result.ok) return
        val fresh = try {
            BackendJson.decodeFromString(Plan.serializer(), result.body)
        } catch (e: SerializationException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        _plan.value = fresh
        prefs.edit {
            putString(OWNER, me)
            putString(CACHE, BackendJson.encodeToString(Plan.serializer(), fresh))
        }
    }

    /** Asks whether the Lab may analyse [packageName], claiming it if so. */
    suspend fun claimLabApp(packageName: String): LabClaim {
        val result = backend.rpc(
            "lab_claim_app",
            buildJsonObject { put("p_package", JsonPrimitive(packageName)) },
        )
        if (result.ok) {
            val reply = try {
                BackendJson.decodeFromString(ClaimReply.serializer(), result.body)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            when {
                reply?.ok == true -> {
                    // A newly kept app changes what the Lab holds; one it keeps already does not.
                    if (_plan.value?.labKept?.contains(packageName) != true) refresh()
                    return LabClaim.Allowed
                }
                reply != null && reply.reason == "upgrade" -> {
                    val kept = reply.labApps.ifEmpty { listOfNotNull(reply.labApp) }
                    return LabClaim.Upgrade(kept, reply.labLimit ?: kept.size.coerceAtLeast(1))
                }
                // A package name the server will not store -- a test APK
                // with an odd name -- is still analysed; there is nothing
                // to keep it to.
                reply?.reason == "bad_package" -> return LabClaim.Allowed
            }
        }
        // No answer. What this phone last heard decides.
        val known = _plan.value ?: return LabClaim.Unknown
        return if (known.labAllows(packageName)) {
            LabClaim.Allowed
        } else {
            LabClaim.Upgrade(known.labKept, known.labLimit ?: 1)
        }
    }

    private fun readCache(): Plan? = prefs.getString(CACHE, null)?.let {
        try {
            BackendJson.decodeFromString(Plan.serializer(), it)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val OWNER = "owner"
        const val CACHE = "plan"
    }
}
