package com.devbangs.onedevs.data.academy

import com.devbangs.onedevs.lab.ApkReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lessons are content, and content is the kind of thing nobody re-reads
 * after writing it. An answer index one past the end, a prerequisite that
 * names a lesson that was renamed, or a {floor} that never got filled in would
 * each look plausible until a learner met it. This reads the real file.
 */
class CurriculumContentTest {

    private val assets: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/assets/academy").takeIf(File::isDirectory) ?: File(it, "src/main/assets/academy") }
        .first(File::isDirectory)

    private val raw = File(assets, "ship-to-play.json").readText()
    private val pathway = Curriculum.parse(raw)
    private val competencies = pathway.competencies
    private val questions = competencies.flatMap { it.questions }

    @Test
    fun `the file is the pathway it says it is`() {
        assertEquals(Curriculum.SHIP_TO_PLAY, pathway.pathway)
        assertTrue(pathway.version >= 1)
        assertTrue(pathway.title.isNotBlank() && pathway.summary.isNotBlank())
    }

    @Test
    fun `every placeholder is filled in`() {
        val filled = Curriculum.fill(raw)
        assertFalse("an unfilled {floor}", "{floor" in filled)
        assertTrue("the floor is quoted somewhere", ApkReport.PLAY_TARGET_SDK_FLOOR.toString() in filled)
        assertTrue("{floor-1} is one below", (ApkReport.PLAY_TARGET_SDK_FLOOR - 1).toString() in filled)
    }

    @Test
    fun `ids are unique and short enough for the server`() {
        val ids = competencies.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        val items = questions.map { it.id }
        assertEquals("question ids repeat", items.size, items.distinct().size)
        items.forEach {
            assertTrue("$it is too long", it.length in 1..40)
            assertTrue("$it is the gate's name", it != Evidence.GATE)
        }
    }

    @Test
    fun `prerequisites exist and come earlier, so the graph has no cycle`() {
        competencies.forEachIndexed { index, c ->
            c.requires.forEach { req ->
                val at = competencies.indexOfFirst { it.id == req }
                assertTrue("${c.id} requires unknown $req", at >= 0)
                assertTrue("${c.id} requires $req, which comes after it", at < index)
            }
        }
    }

    @Test
    fun `the free competencies come first, and there are some`() {
        val free = competencies.takeWhile { it.free }
        assertTrue(free.isNotEmpty())
        assertTrue("a free competency after a paid one", competencies.drop(free.size).none { it.free })
        assertTrue("a gate needs the Lab, which a free plan keeps to one app", free.none { it.gate != null })
    }

    @Test
    fun `every lesson has the whole unit anatomy`() {
        competencies.forEach { c ->
            assertTrue("${c.id} outcome", c.outcome.isNotBlank())
            assertTrue("${c.id} minutes", c.minutes in 5..60)
            assertTrue("${c.id} model", c.model.size >= 2 && c.model.all { it.isNotBlank() })
            assertTrue("${c.id} example", c.example.code.any { it.isNotBlank() })
            assertTrue("${c.id} has no formative question", c.formative.isNotEmpty())
            assertTrue("${c.id} has no debug question", c.questions.any { it.kind == QuestionKind.Debug })
            assertTrue("${c.id} has no transfer question", c.questions.any { it.kind == QuestionKind.Transfer })
            c.gate?.let { assertTrue("${c.id} gate task", it.task.isNotBlank()) }
        }
    }

    @Test
    fun `every question can be asked again differently, with hints that build`() {
        questions.forEach { q ->
            assertTrue("${q.id} needs a second variant for retries", q.variants.size in 2..10)
            assertTrue("${q.id} needs at least two hints", q.hints.size >= 2)
            q.variants.forEach { v ->
                assertTrue("${q.id} prompt", v.prompt.isNotBlank())
                assertTrue("${q.id} why", v.why.isNotBlank())
            }
        }
    }

    @Test
    fun `debug questions point at a line, the rest pick a choice`() {
        questions.forEach { q ->
            q.variants.forEachIndexed { i, v ->
                val where = "${q.id}[$i]"
                assertEquals("$where: debug and only debug picks a line", q.kind == QuestionKind.Debug, v.picksLine)
                if (v.picksLine) {
                    assertTrue("$where code", v.code.size >= 3)
                    assertTrue("$where answer is a line", v.answer in v.code.indices)
                    assertTrue("$where answer points at a blank line", v.code[v.answer].isNotBlank())
                } else {
                    assertTrue("$where choices", v.choices.size in 3..4)
                    assertTrue("$where answer", v.answer in v.choices.indices)
                    assertEquals("$where repeats a choice", v.choices.size, v.choices.distinct().size)
                }
            }
        }
    }

    @Test
    fun `the capstone is the closed test the server counts`() {
        assertEquals(12, pathway.capstone.testers)
        assertEquals(14, pathway.capstone.days)
    }

    @Test
    fun `a translation falls back to the English source`() {
        val files = setOf("ship-to-play.json", "ship-to-play.fr.json")
        assertEquals("ship-to-play.fr.json", Curriculum.fileFor(Curriculum.SHIP_TO_PLAY, "fr", files))
        assertEquals("ship-to-play.json", Curriculum.fileFor(Curriculum.SHIP_TO_PLAY, "es", files))
        assertEquals("ship-to-play.json", Curriculum.fileFor(Curriculum.SHIP_TO_PLAY, "", files))
    }
}

/**
 * The server keeps its own short copy of the pathway -- which competencies
 * exist, which are free, which have a gate and how many questions of each
 * kind -- because it judges credentials without the lessons. The copy is in
 * the migration; this keeps the two from drifting.
 */
class AcademyCatalogueTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "supabase/migrations").isDirectory }

    private val pathway = Curriculum.parse(File(root, "app/src/main/assets/academy/ship-to-play.json").readText())

    private data class Row(
        val id: String,
        val position: Int,
        val free: Boolean,
        val artifact: String?,
        val formative: Int,
        val summative: Int,
    )

    private val rows: List<Row> = run {
        val sql = File(root, "supabase/migrations").listFiles().orEmpty()
            .filter { it.name.endsWith(".sql") }.sortedBy { it.name }
            .joinToString("\n") { it.readText() }
        Regex("""\('ship-to-play', '([a-z0-9-]+)', (\d+), (true|false), (null|'[a-z_]+'), (\d+), (\d+)\)""")
            .findAll(sql)
            .map {
                val (id, position, free, artifact, formative, summative) = it.destructured
                Row(id, position.toInt(), free.toBoolean(), artifact.trim('\'').takeIf { a -> a != "null" }, formative.toInt(), summative.toInt())
            }
            .toList()
    }

    private val wire = mapOf(
        ArtifactGate.TargetSdk to "target_sdk",
        ArtifactGate.Signing to "signing",
        ArtifactGate.ReleaseBuild to "release_build",
        ArtifactGate.NativeCode to "native_code",
    )

    @Test
    fun `the server lists the same competencies in the same order`() {
        assertEquals(pathway.competencies.map { it.id }, rows.sortedBy { it.position }.map { it.id })
    }

    @Test
    fun `and agrees on what is free, what is gated and how many questions of each kind`() {
        val byId = rows.associateBy { it.id }
        pathway.competencies.forEach { c ->
            val row = byId.getValue(c.id)
            assertEquals("${c.id} free", c.free, row.free)
            assertEquals("${c.id} gate", c.gate?.let { wire.getValue(it.artifact) }, row.artifact)
            assertEquals("${c.id} formative", c.formative.size, row.formative)
            assertEquals("${c.id} summative", c.summative.size, row.summative)
        }
    }

    @Test
    fun `the bars are the same on both sides`() {
        val sql = File(root, "supabase/migrations/0035_academy.sql").readText()
        assertTrue("ceil(c.formative * 0.80)" in sql && Mastery.FORMATIVE_BAR == 0.80)
        assertTrue("ceil(c.summative * 0.85)" in sql && Mastery.SUMMATIVE_BAR == 0.85)
        assertTrue("interval '10 minutes'" in sql && Mastery.REASSESS_AFTER_MS == 10L * 60 * 1000)
    }
}
