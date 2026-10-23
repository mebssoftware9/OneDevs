package com.devbangs.onedevs.data.academy

import android.content.Context
import com.devbangs.onedevs.lab.ApkReport
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pathway, as the lessons in assets/academy write it.
 *
 * Lessons are content, not code: each is a JSON file with a version, read
 * once and held for the life of the process. The server never sees the
 * lessons, only what the learner did with them, and stores the version each
 * answer was given against so a credential can say what it was earned on.
 */
@Serializable
data class Pathway(
    val pathway: String,
    val version: Int,
    val title: String,
    val summary: String,
    val capstone: CapstoneSpec,
    val competencies: List<Competency>,
) {
    fun competency(id: String): Competency? = competencies.firstOrNull { it.id == id }
}

/** The project that ends the pathway, measured by the server rather than answered. */
@Serializable
data class CapstoneSpec(
    val title: String,
    val outcome: String,
    val testers: Int,
    val days: Int,
)

/**
 * One thing a learner can show they can do: a lesson, the questions that
 * prove it, and for some a gate their own build has to pass.
 */
@Serializable
data class Competency(
    val id: String,
    val title: String,
    /** What the learner can do afterwards, stated as something observable. */
    val outcome: String,
    val minutes: Int,
    /** Open on Community. The rest need Premium or Pro. */
    val free: Boolean,
    /** Recommended first, never enforced: a learner may test out of what they know. */
    val requires: List<String> = emptyList(),
    /** The mental model, a paragraph at a time. */
    val model: List<String>,
    val example: Example,
    val questions: List<Question>,
    val gate: Gate? = null,
) {
    val formative: List<Question> get() = questions.filter { !it.kind.summative }
    val summative: List<Question> get() = questions.filter { it.kind.summative }
}

/** A worked example: code, line by line, and what to notice in it. */
@Serializable
data class Example(
    val code: List<String>,
    val notes: List<String> = emptyList(),
)

/**
 * The four kinds of question. Concept and predict check the model as it is
 * taught; debug and transfer check that it survives a broken program and a
 * situation the lesson never showed, which is why they carry the higher bar.
 */
@Serializable
enum class QuestionKind {
    @SerialName("concept") Concept,
    @SerialName("predict") Predict,
    @SerialName("debug") Debug,
    @SerialName("transfer") Transfer,
    ;

    val summative: Boolean get() = this == Debug || this == Transfer

    /** The name the server stores, which is also the name in the lesson file. */
    val wire: String get() = name.lowercase(Locale.ROOT)
}

/**
 * One question, in several variants. A learner who misses it meets a
 * different variant next time, so remembering the position of an answer is
 * not the same as knowing it.
 */
@Serializable
data class Question(
    val id: String,
    val kind: QuestionKind,
    /** From a nudge to a narrowing question; never the answer itself. */
    val hints: List<String> = emptyList(),
    val variants: List<Variant>,
)

/**
 * One variant. With [choices] the answer is an index into them; without, it
 * is the index of a line of [code] the learner has to point at.
 */
@Serializable
data class Variant(
    val prompt: String,
    val code: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
    val answer: Int,
    /** Why the answer is the answer, shown after every attempt, right or wrong. */
    val why: String,
) {
    val picksLine: Boolean get() = choices.isEmpty()
}

/** What a gate reads from the learner's own APK. Each is a set of the Lab's checks. */
@Serializable
enum class ArtifactGate {
    @SerialName("target_sdk") TargetSdk,
    @SerialName("signing") Signing,
    @SerialName("release_build") ReleaseBuild,
    @SerialName("native_code") NativeCode,
}

@Serializable
data class Gate(val artifact: ArtifactGate, val task: String)

/**
 * Reading lessons.
 *
 * English is the source, in academy/<id>.json. A translation is the same file
 * as academy/<id>.<language>.json and is used when it exists; until it does,
 * a reader in French gets the app in French and the lessons in English, which
 * is better than no lessons. Lessons quote numbers that move -- Play's target
 * API floor moves every August -- as {floor}, filled in here from the one
 * place the Lab keeps it.
 */
object Curriculum {
    const val SHIP_TO_PLAY = "ship-to-play"

    private val json = Json { ignoreUnknownKeys = true }

    fun fill(text: String, floor: Int = ApkReport.PLAY_TARGET_SDK_FLOOR): String =
        text.replace("{floor-1}", (floor - 1).toString()).replace("{floor}", floor.toString())

    fun parse(text: String, floor: Int = ApkReport.PLAY_TARGET_SDK_FLOOR): Pathway =
        json.decodeFromString(Pathway.serializer(), fill(text, floor))

    /** The file to read for [id] in [language], falling back to the English source. */
    fun fileFor(id: String, language: String, available: Set<String>): String {
        val translated = "$id.$language.json"
        return if (language.isNotEmpty() && translated in available) translated else "$id.json"
    }

    fun load(context: Context, id: String = SHIP_TO_PLAY): Pathway {
        val language = context.resources.configuration.locales[0].language
        val available = try {
            context.assets.list("academy")?.toSet().orEmpty()
        } catch (e: IOException) {
            emptySet()
        }
        val name = fileFor(id, language, available)
        return parse(context.assets.open("academy/$name").bufferedReader().use { it.readText() })
    }
}
