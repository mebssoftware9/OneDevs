package com.devbangs.onedevs.data.academy

import java.util.UUID
import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One answer, or one gate check, as the learner gave it.
 *
 * Written on the phone the moment it happens and sent when there is a
 * connection. The id is made here so a retried send is the same row on the
 * server, never a second answer.
 */
@Serializable
data class Evidence(
    val id: String = UUID.randomUUID().toString(),
    val competency: String,
    /** A question's id, or [GATE]. */
    val item: String,
    /** A [QuestionKind.wire] name, or [ARTIFACT]. */
    val kind: String,
    val variant: Int = 0,
    val correct: Boolean,
    val hinted: Boolean = false,
    /** The lesson file's version when this was answered. */
    val version: Int,
    /** The app a gate check read. */
    @SerialName("package") val packageName: String? = null,
    val at: Long,
    /** Whether the server has it. Only ever goes from false to true. */
    val sent: Boolean = false,
) {
    val artifact: Boolean get() = kind == ARTIFACT

    companion object {
        const val GATE = "gate"
        const val ARTIFACT = "artifact"
    }
}

/** Where a learner stands on one competency. */
enum class Standing {
    NotStarted,

    /** Some evidence, not yet enough. */
    Learning,

    /** Enough evidence, and the review it is owed is not due yet. */
    Verified,

    /** Verified, and due for a review. A review missed sends it back to Learning. */
    ReviewDue,
}

/** One question's state, from what was answered and when. */
enum class QuestionState {
    New,

    /** The latest answer without a hint was wrong. */
    Missed,

    /**
     * Right, but within [Mastery.REASSESS_AFTER_MS] of getting it wrong. It
     * counts once it is answered right again after that.
     */
    TooSoon,

    /** Right only with a hint so far. */
    Hinted,

    Secure,
}

data class QuestionStatus(
    val id: String,
    val kind: QuestionKind,
    val state: QuestionState,
    /** For [QuestionState.TooSoon]: when answering it again will count. */
    val countsFrom: Long? = null,
    val misses: Int = 0,
)

/** Everything the screens need to say where a competency stands and why. */
data class Progress(
    val competency: String,
    val standing: Standing,
    val questions: List<QuestionStatus>,
    val secureFormative: Int,
    val formative: Int,
    val secureSummative: Int,
    val summative: Int,
    /** Null when the competency has no gate. */
    val gatePassed: Boolean?,
    /** When it last became verified, or null. */
    val verifiedAt: Long?,
    /** When the next review is owed, while verified. */
    val reviewAt: Long?,
    /** Reviews passed on time since it was verified, which sets the next interval. */
    val step: Int,
) {
    val answeredAny: Boolean get() = questions.any { it.state != QuestionState.New } || gatePassed == true
    val verified: Boolean get() = standing == Standing.Verified || standing == Standing.ReviewDue
    val formativeMet: Boolean get() = secureFormative >= Mastery.needed(formative, Mastery.FORMATIVE_BAR)
    val summativeMet: Boolean get() = secureSummative >= Mastery.needed(summative, Mastery.SUMMATIVE_BAR)
}

/** One question to ask, in the variant and order it will be shown in. */
data class Asked(
    val competency: String,
    val question: Question,
    val variantIndex: Int,
    /** Display position to original choice index. Empty for a line pick. */
    val order: List<Int>,
    /** Asked again in the same session after a miss. */
    val retry: Boolean = false,
) {
    val variant: Variant get() = question.variants[variantIndex]

    /** Whether the learner's pick -- a display position, or a line -- is the answer. */
    fun isRight(pick: Int): Boolean =
        if (variant.picksLine) pick == variant.answer else order.getOrNull(pick) == variant.answer
}

/**
 * The mastery rules: explainable, and the same ones the server applies.
 *
 * A question is secure when the latest attempt at it without a hint was right
 * and was not made within ten minutes of getting it wrong. Guessing again at
 * once proves nothing; coming back to it later does. A competency is verified
 * when 80% of its formative questions and 85% of its summative ones are
 * secure, and its gate, if it has one, has passed on the learner's own APK.
 *
 * Verified is not forever. Each competency is owed a review at growing
 * intervals; answering right when it is due moves to the next interval, and a
 * miss makes the question insecure again, which is what "needs review" means.
 */
object Mastery {
    const val FORMATIVE_BAR = 0.80
    const val SUMMATIVE_BAR = 0.85
    const val REASSESS_AFTER_MS = 10L * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Days until each review. Answering on time moves one step along. */
    val INTERVAL_DAYS = listOf(1, 3, 7, 16, 35)

    /** A review session is short: enough to interleave, not enough to dread. */
    const val REVIEW_LIMIT = 8

    fun needed(total: Int, bar: Double): Int = ceil(total * bar - 1e-9).toInt()

    private class Track(val kind: QuestionKind) {
        var lastUnhinted: Evidence? = null
        var hintedRight = false
        val wrongs = mutableListOf<Long>()

        fun apply(e: Evidence) {
            if (!e.correct) wrongs += e.at
            if (e.hinted) {
                if (e.correct) hintedRight = true
            } else {
                lastUnhinted = e
            }
        }

        /** A wrong attempt in the ten minutes up to the latest unhinted answer. */
        fun missedJustBefore(): Long? {
            val last = lastUnhinted ?: return null
            return wrongs.lastOrNull { it <= last.at && it > last.at - REASSESS_AFTER_MS }
        }

        fun state(): QuestionState {
            val last = lastUnhinted
            return when {
                last == null && hintedRight -> QuestionState.Hinted
                last == null && wrongs.isNotEmpty() -> QuestionState.Missed
                last == null -> QuestionState.New
                !last.correct -> QuestionState.Missed
                missedJustBefore() != null -> QuestionState.TooSoon
                else -> QuestionState.Secure
            }
        }
    }

    /**
     * Replays [evidence] for one competency and says where it stands at [now].
     * Replaying, rather than reading the last answer, is what makes the review
     * schedule follow the order things actually happened in.
     */
    fun progress(competency: Competency, evidence: List<Evidence>, now: Long): Progress {
        val tracks = competency.questions.associate { it.id to Track(it.kind) }
        var gatePassed = false
        var verifiedAt: Long? = null
        var reviewAt: Long? = null
        var step = 0

        fun securedNow(): Boolean {
            val secure = tracks.values.filter { it.state() == QuestionState.Secure }
            val f = secure.count { !it.kind.summative }
            val s = secure.count { it.kind.summative }
            val gateOk = competency.gate == null || gatePassed
            return f >= needed(competency.formative.size, FORMATIVE_BAR) &&
                s >= needed(competency.summative.size, SUMMATIVE_BAR) &&
                gateOk
        }

        evidence.asSequence()
            .filter { it.competency == competency.id }
            .sortedBy { it.at }
            .forEach { e ->
                if (e.artifact) {
                    if (e.correct) gatePassed = true
                } else {
                    tracks[e.item]?.apply(e) ?: return@forEach
                }
                val secured = securedNow()
                val due = reviewAt
                when {
                    verifiedAt == null && secured -> {
                        verifiedAt = e.at
                        step = 0
                        reviewAt = e.at + INTERVAL_DAYS[0] * DAY_MS
                    }
                    verifiedAt != null && !secured -> {
                        verifiedAt = null
                        reviewAt = null
                        step = 0
                    }
                    // A right answer, on its own, once the review is owed:
                    // the next one is further away.
                    verifiedAt != null && due != null && e.at >= due &&
                        e.correct && !e.hinted && !e.artifact -> {
                        step = (step + 1).coerceAtMost(INTERVAL_DAYS.lastIndex)
                        reviewAt = e.at + INTERVAL_DAYS[step] * DAY_MS
                    }
                }
            }

        val statuses = competency.questions.map { q ->
            val t = tracks.getValue(q.id)
            val state = t.state()
            QuestionStatus(
                id = q.id,
                kind = q.kind,
                state = state,
                countsFrom = if (state == QuestionState.TooSoon) t.missedJustBefore()?.plus(REASSESS_AFTER_MS) else null,
                misses = t.wrongs.size,
            )
        }
        val secure = statuses.filter { it.state == QuestionState.Secure }
        val owed = reviewAt
        val standing = when {
            verifiedAt != null && owed != null && now >= owed -> Standing.ReviewDue
            verifiedAt != null -> Standing.Verified
            statuses.any { it.state != QuestionState.New } || gatePassed -> Standing.Learning
            else -> Standing.NotStarted
        }
        return Progress(
            competency = competency.id,
            standing = standing,
            questions = statuses,
            secureFormative = secure.count { !it.kind.summative },
            formative = competency.formative.size,
            secureSummative = secure.count { it.kind.summative },
            summative = competency.summative.size,
            gatePassed = if (competency.gate == null) null else gatePassed,
            verifiedAt = verifiedAt,
            reviewAt = reviewAt,
            step = step,
        )
    }

    fun progress(pathway: Pathway, evidence: List<Evidence>, now: Long): Map<String, Progress> {
        val byCompetency = evidence.groupBy { it.competency }
        return pathway.competencies.associate { c ->
            c.id to progress(c, byCompetency[c.id].orEmpty(), now)
        }
    }

    /**
     * The competency to work on next: one owed a review first, then the first
     * unfinished one whose recommended prerequisites are done, then the first
     * unfinished one at all.
     */
    fun next(pathway: Pathway, progress: Map<String, Progress>, open: (Competency) -> Boolean): Competency? {
        val unfinished = pathway.competencies.filter { open(it) && progress[it.id]?.verified != true }
        return pathway.competencies.firstOrNull { open(it) && progress[it.id]?.standing == Standing.ReviewDue }
            ?: unfinished.firstOrNull { c -> c.requires.all { progress[it]?.verified == true } }
            ?: unfinished.firstOrNull()
    }

    /**
     * A practice session for one competency: what is not yet secure first, in
     * lesson order, then the rest. Each question in a variant other than the
     * one last shown, and with its choices in a fresh order.
     */
    fun practice(competency: Competency, evidence: List<Evidence>, now: Long, random: Random): List<Asked> {
        val p = progress(competency, evidence, now)
        val state = p.questions.associate { it.id to it.state }
        val (todo, done) = competency.questions.partition { state[it.id] != QuestionState.Secure }
        return (todo + done).map { ask(competency.id, it, evidence, random) }
    }

    /**
     * A review across the pathway: up to two questions from each competency
     * owed one -- the ones missed most first -- taken in turn, so the session
     * mixes topics rather than running through one and then the next.
     */
    fun review(
        pathway: Pathway,
        evidence: List<Evidence>,
        now: Long,
        random: Random,
        open: (Competency) -> Boolean,
    ): List<Asked> {
        val progress = progress(pathway, evidence, now)
        val due = pathway.competencies.filter { open(it) && progress[it.id]?.standing == Standing.ReviewDue }
        val picks = due.map { c ->
            val misses = progress.getValue(c.id).questions.associate { it.id to it.misses }
            val formative = c.formative.sortedByDescending { misses[it.id] ?: 0 }.take(1)
            val summative = c.summative.sortedByDescending { misses[it.id] ?: 0 }.take(1)
            (summative + formative).map { ask(c.id, it, evidence, random) }
        }
        val mixed = mutableListOf<Asked>()
        var round = 0
        while (mixed.size < REVIEW_LIMIT && picks.any { it.size > round }) {
            picks.forEach { list -> list.getOrNull(round)?.let { if (mixed.size < REVIEW_LIMIT) mixed += it } }
            round++
        }
        return mixed
    }

    /** How many competencies are owed a review now. */
    fun dueCount(progress: Map<String, Progress>): Int = progress.values.count { it.standing == Standing.ReviewDue }

    /**
     * The same question again after a miss, later in the session and in a
     * different variant. It helps the learning; it does not count yet.
     */
    fun retry(asked: Asked, random: Random): Asked {
        val q = asked.question
        val variant = if (q.variants.size > 1) (asked.variantIndex + 1) % q.variants.size else asked.variantIndex
        return Asked(asked.competency, q, variant, order(q.variants[variant], random), retry = true)
    }

    private fun ask(competency: String, q: Question, evidence: List<Evidence>, random: Random): Asked {
        val last = evidence.lastOrNull { it.competency == competency && it.item == q.id }
        val variant = when {
            q.variants.size == 1 -> 0
            last != null -> (last.variant + 1) % q.variants.size
            else -> random.nextInt(q.variants.size)
        }
        return Asked(competency, q, variant, order(q.variants[variant], random))
    }

    private fun order(v: Variant, random: Random): List<Int> =
        if (v.picksLine) emptyList() else v.choices.indices.shuffled(random)
}
