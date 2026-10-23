package com.devbangs.onedevs.data.academy

import android.content.Context
import com.devbangs.onedevs.data.backend.AccountState
import com.devbangs.onedevs.data.backend.Backend
import com.devbangs.onedevs.data.backend.BackendJson
import com.devbangs.onedevs.data.backend.quietly
import com.devbangs.onedevs.data.plans.Plan
import com.devbangs.onedevs.data.plans.Tier
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Whether a plan opens a competency. Community opens the free ones; Premium and Pro open all. */
fun Competency.openOn(plan: Plan?): Boolean = free || plan?.tier == Tier.Premium || plan?.tier == Tier.Pro

/** How far the learner's own app is toward the capstone, as the server counts it. */
@Serializable
data class CapstoneProgress(
    val listing: String? = null,
    val title: String? = null,
    val testers: Int = 0,
    val days: Int = 0,
    @SerialName("needed_testers") val neededTesters: Int = 12,
    @SerialName("needed_days") val neededDays: Int = 14,
    val met: Boolean = false,
)

/** A credential the server issued. */
@Serializable
data class Credential(
    val key: String,
    val token: String,
    val rubric: Int = 1,
    val version: Int = 1,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
) {
    val revoked: Boolean get() = revokedAt != null
    val issuedMillis: Long? get() = AcademyWire.millis(issuedAt)

    /** The public link anyone can open to check it. */
    fun link(supabaseUrl: String): String = "${supabaseUrl.trimEnd('/')}/functions/v1/verify?c=$token"

    companion object {
        const val FOUNDATIONS = "foundations"
        const val CAPSTONE = "capstone"
        const val PATHWAY = "ship-to-play"
    }
}

/** How asking for a credential went. */
sealed interface ClaimOutcome {
    data class Issued(val credential: Credential) : ClaimOutcome

    /** Not earned yet: [missing] names competencies, and "capstone". */
    data class NotYet(val missing: List<String>) : ClaimOutcome

    data object Failed : ClaimOutcome
}

/** One row of academy_mine(). */
@Serializable
internal data class WireEvidence(
    val id: String,
    val competency: String,
    val item: String,
    val kind: String,
    val variant: Int = 0,
    val correct: Boolean,
    val hinted: Boolean = false,
    val version: Int = 1,
    @SerialName("package") val packageName: String? = null,
    val at: String,
)

@Serializable
private data class RecordReply(val ok: Boolean = false, val reason: String? = null)

@Serializable
private data class ClaimReply(
    val ok: Boolean = false,
    val reason: String? = null,
    val missing: List<String> = emptyList(),
    val credential: Credential? = null,
)

/**
 * The rules for the evidence log, apart from the file that holds it, so they
 * can be tested without a device.
 */
internal object AcademyWire {
    /** academy_record takes at most this many at a time. */
    const val BATCH = 100

    fun millis(iso: String): Long? = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    fun toWire(e: Evidence, pathway: String): JsonObject = buildJsonObject {
        put("id", e.id)
        put("pathway", pathway)
        put("competency", e.competency)
        put("item", e.item)
        put("kind", e.kind)
        put("variant", e.variant)
        put("correct", e.correct)
        put("hinted", e.hinted)
        put("version", e.version)
        e.packageName?.let { put("package", it) }
        put("at", Instant.ofEpochMilli(e.at).toString())
    }

    /**
     * What the server holds, folded into what the phone holds. The phone's
     * copy wins for anything both have -- it is the same answer -- except that
     * anything the server has is, by definition, sent.
     */
    fun merge(local: List<Evidence>, remote: List<Evidence>): List<Evidence> {
        val remoteIds = remote.map { it.id }.toSet()
        val localIds = local.map { it.id }.toSet()
        val kept = local.map { if (it.id in remoteIds && !it.sent) it.copy(sent = true) else it }
        return (kept + remote.filter { it.id !in localIds }).sortedBy { it.at }
    }

    fun markSent(log: List<Evidence>, ids: Set<String>): List<Evidence> =
        log.map { if (it.id in ids && !it.sent) it.copy(sent = true) else it }

    fun unsentBatches(log: List<Evidence>): List<List<Evidence>> = log.filter { !it.sent }.chunked(BATCH)

    fun fromWire(w: WireEvidence): Evidence? {
        val at = millis(w.at) ?: return null
        return Evidence(
            id = w.id,
            competency = w.competency,
            item = w.item,
            kind = w.kind,
            variant = w.variant,
            correct = w.correct,
            hinted = w.hinted,
            version = w.version,
            packageName = w.packageName,
            at = at,
            sent = true,
        )
    }
}

/**
 * The learner's answers, kept on the phone and on the server.
 *
 * Every answer is written to disk the moment it is given, and sent behind it.
 * Practising on a train works: the answers wait, and mastery is worked out on
 * the phone from what is on the phone. A new phone, or a reinstall, reads the
 * answers back from the server before anything is shown, so nobody's progress
 * lives on one device. Each account has its own file, so two people sharing a
 * phone never see each other's.
 *
 * The capstone and credentials are the server's alone: the phone asks.
 */
class AcademyStore(
    private val context: Context,
    private val backend: Backend,
    private val account: AccountState,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "academy")
    private val lock = Mutex()
    private val sending = Mutex()

    private val _pathway = MutableStateFlow<Pathway?>(null)
    val pathway: StateFlow<Pathway?> = _pathway.asStateFlow()

    /** The lessons could not be read: a broken install, not a state to wait out. */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    private val _evidence = MutableStateFlow<List<Evidence>>(emptyList())
    val evidence: StateFlow<List<Evidence>> = _evidence.asStateFlow()

    private val _capstone = MutableStateFlow<CapstoneProgress?>(null)
    val capstone: StateFlow<CapstoneProgress?> = _capstone.asStateFlow()

    /** Null until read; an empty list is an answer. */
    private val _credentials = MutableStateFlow<List<Credential>?>(null)
    val credentials: StateFlow<List<Credential>?> = _credentials.asStateFlow()

    private var owner: String? = null

    init {
        scope.launch {
            _pathway.value = withContext(Dispatchers.IO) {
                try {
                    Curriculum.load(context)
                } catch (e: IOException) {
                    null
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            _unreadable.value = _pathway.value == null
        }
        scope.launch {
            account.session.map { it?.userId }.distinctUntilChanged().collect { user ->
                lock.withLock {
                    owner = user
                    _evidence.value = if (user == null) emptyList() else read(user)
                }
                _capstone.value = null
                _credentials.value = null
                if (user != null) {
                    restore()
                    sync()
                    refresh()
                }
            }
        }
    }

    /** Records an answer to [asked] and says whether it was right. */
    suspend fun answer(asked: Asked, pick: Int, hinted: Boolean): Boolean {
        val right = asked.isRight(pick)
        record(
            Evidence(
                competency = asked.competency,
                item = asked.question.id,
                kind = asked.question.kind.wire,
                variant = asked.variantIndex,
                correct = right,
                hinted = hinted,
                version = _pathway.value?.version ?: 1,
                at = System.currentTimeMillis(),
            ),
        )
        return right
    }

    /** Records that a competency's gate passed, or did not, on [packageName]. */
    suspend fun gate(competency: String, packageName: String, passed: Boolean) = record(
        Evidence(
            competency = competency,
            item = Evidence.GATE,
            kind = Evidence.ARTIFACT,
            correct = passed,
            version = _pathway.value?.version ?: 1,
            packageName = packageName,
            at = System.currentTimeMillis(),
        ),
    )

    private suspend fun record(e: Evidence) {
        lock.withLock {
            val user = owner ?: return
            val next = _evidence.value + e
            write(user, next)
            _evidence.value = next
        }
        scope.launch { sync() }
    }

    /** Sends whatever the server does not have yet. Quiet: nobody is waiting on it. */
    suspend fun sync() = sending.withLock {
        val pathwayId = _pathway.value?.pathway ?: Curriculum.SHIP_TO_PLAY
        for (batch in AcademyWire.unsentBatches(_evidence.value)) {
            val result = quietly {
                backend.rpc(
                    "academy_record",
                    buildJsonObject { put("p_events", JsonArray(batch.map { AcademyWire.toWire(it, pathwayId) })) },
                )
            }
            val reply = if (result.ok) decode(RecordReply.serializer(), result.body) else null
            if (reply?.ok != true) return@withLock
            lock.withLock {
                val user = owner ?: return@withLock
                val next = AcademyWire.markSent(_evidence.value, batch.map { it.id }.toSet())
                write(user, next)
                _evidence.value = next
            }
        }
    }

    /** Reads the capstone and the credentials again. */
    suspend fun refresh() {
        quietly { backend.rpc("academy_capstone", JsonObject(emptyMap())) }
            .takeIf { it.ok }
            ?.let { decode(CapstoneProgress.serializer(), it.body) }
            ?.let { _capstone.value = it }
        quietly { backend.rpc("my_credentials", JsonObject(emptyMap())) }
            .takeIf { it.ok }
            ?.let { decode(ListSerializer(Credential.serializer()), it.body) }
            ?.let { _credentials.value = it }
    }

    /**
     * Asks for a credential. Everything not yet sent goes first, so the server
     * judges the same answers the phone does.
     */
    suspend fun claim(key: String): ClaimOutcome {
        sync()
        val result = backend.rpc("academy_claim", buildJsonObject { put("p_key", JsonPrimitive(key)) })
        val reply = if (result.ok) decode(ClaimReply.serializer(), result.body) else null
        return when {
            reply == null -> ClaimOutcome.Failed
            reply.ok && reply.credential != null -> {
                refresh()
                ClaimOutcome.Issued(reply.credential)
            }
            reply.reason == "not_yet" -> ClaimOutcome.NotYet(reply.missing)
            else -> ClaimOutcome.Failed
        }
    }

    /** On a phone with no answers yet, the server's copy. */
    private suspend fun restore() {
        if (_evidence.value.isNotEmpty()) return
        val pathwayId = _pathway.value?.pathway ?: Curriculum.SHIP_TO_PLAY
        val result = quietly {
            backend.rpc("academy_mine", buildJsonObject { put("p_pathway", JsonPrimitive(pathwayId)) })
        }
        if (!result.ok) return
        val rows = decode(ListSerializer(WireEvidence.serializer()), result.body) ?: return
        val remote = rows.mapNotNull(AcademyWire::fromWire)
        lock.withLock {
            val user = owner ?: return
            val next = AcademyWire.merge(_evidence.value, remote)
            write(user, next)
            _evidence.value = next
        }
    }

    private fun fileFor(user: String) = File(dir, "evidence-$user.json")

    private suspend fun read(user: String): List<Evidence> = withContext(Dispatchers.IO) {
        val file = fileFor(user)
        if (!file.exists()) return@withContext emptyList()
        try {
            BackendJson.decodeFromString(ListSerializer(Evidence.serializer()), file.readText())
        } catch (e: SerializationException) {
            // Unreadable here is not lost: whatever was sent is on the server,
            // and restore() reads it back.
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** To a temporary file, then renamed: a write cut short leaves the old log, never half of one. */
    private suspend fun write(user: String, log: List<Evidence>) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = fileFor(user)
        val staging = File(dir, "evidence-$user.json.tmp")
        staging.writeText(BackendJson.encodeToString(ListSerializer(Evidence.serializer()), log))
        if (!staging.renameTo(file)) file.writeText(staging.readText())
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: String): T? = try {
        BackendJson.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
