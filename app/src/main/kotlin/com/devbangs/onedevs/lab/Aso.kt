package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R
import java.text.Normalizer
import java.util.Locale

/** The text of a Play listing, as the developer has written it so far. */
data class Listing(
    val title: String = "",
    val short: String = "",
    val full: String = "",
    /** What the developer wants to be found for, one phrase per entry. */
    val keywords: List<String> = emptyList(),
) {
    val empty: Boolean get() = title.isBlank() && short.isBlank() && full.isBlank()
}

/** Where one keyword appears in a listing. */
data class Placement(
    val keyword: String,
    val inTitle: Boolean,
    val inShort: Boolean,
    /** In the part of the full description shown before "About this app" is expanded. */
    val aboveFold: Boolean,
    val inFull: Int,
    /** Occurrences per hundred words of the full description. */
    val density: Double,
) {
    val anywhere: Boolean get() = inTitle || inShort || inFull > 0
}

/** One part of the ASO score. */
data class ScorePart(val label: Int, val points: Int, val max: Int)

/**
 * Store listing text against Play's metadata policy and the rules of thumb
 * that decide whether it is found.
 *
 * All of it is arithmetic on the text the developer types. Search volume,
 * difficulty and rankings are Google's numbers and are not here; what is here
 * is everything that does not need them.
 */
object Aso {

    const val TITLE_MAX = 30
    const val SHORT_MAX = 80
    const val FULL_MAX = 4000

    /** Roughly what Play shows of the full description before it is expanded. */
    const val FOLD = 170

    /** Below this the full description reads as unfinished. */
    const val FULL_THIN = 600

    /** Above this, a keyword reads as repeated for the algorithm rather than the reader. */
    const val DENSITY_HIGH = 3.0

    /**
     * Claims Play's metadata policy does not allow anywhere in a listing:
     * rankings, awards and download counts the store has not verified.
     */
    private val CLAIMS = listOf(
        "best", "#1", "no.1", "number one", "top rated", "app of the year", "editor's choice",
        "million downloads", "mejor", "meilleur", "melhor",
    )

    /**
     * Price and promotion words, which the policy keeps out of the title in
     * particular. "Free" is fine in a description; in a title it is a promise
     * the store shows next to every search result.
     */
    private val PROMO = CLAIMS + listOf(
        "free", "top", "new", "sale", "discount", "cheap", "download now", "install now", "hot", "bonus",
        "cashback", "gratis", "nuevo", "oferta", "descuento", "gratuit", "nouveau", "promo", "réduction",
        "grátis", "novo", "desconto",
    )

    fun keywords(text: String): List<String> = text.split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { fold(it) }

    /** Lowercase, accents removed, so "Café" is found by "cafe". */
    internal fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    internal fun words(text: String): List<String> =
        fold(text).split(Regex("[^\\p{L}\\p{N}#.']+")).map { it.trim('.', '\'') }.filter { it.isNotEmpty() }

    /** Occurrences of [phrase] as whole words. */
    internal fun count(text: String, phrase: String): Int {
        val target = words(phrase)
        if (target.isEmpty()) return 0
        val all = words(text)
        return (0..all.size - target.size).count { i -> all.subList(i, i + target.size) == target }
    }

    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]")

    internal fun emoji(text: String): Boolean = EMOJI.containsMatchIn(text)

    /** Shouting: most letters capitals, in text long enough for that to mean something. */
    internal fun shouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 6 && letters.count { it.isUpperCase() } * 10 >= letters.length * 8
    }

    internal fun promo(text: String, title: Boolean = true): List<String> =
        (if (title) PROMO else CLAIMS).filter { count(text, it) > 0 }

    /** Punctuation doing a word's job: "!!!", "***", "$$$". */
    private val NOISE = Regex("([!?*$€~|•★☆])\\1{1,}")

    private fun length(text: String) = text.codePointCount(0, text.length)

    private fun lengthCheck(text: String, max: Int, field: Msg): Check {
        val n = length(text.trim())
        return when {
            n == 0 -> Check(Status.Fail, str(R.string.aso_empty, field))
            n > max -> Check(Status.Fail, str(R.string.aso_too_long, field, num(n), num(max)), str(R.string.aso_too_long_d))
            else -> Check(Status.Pass, str(R.string.aso_length, field, num(n), num(max)))
        }
    }

    /** Policy problems any field can have. */
    private fun policy(text: String, field: Msg, strict: Boolean): List<Check> = buildList {
        val promo = promo(text, title = strict)
        if (promo.isNotEmpty()) {
            add(
                Check(
                    if (strict) Status.Fail else Status.Warn,
                    str(R.string.aso_promo, field),
                    str(R.string.aso_promo_d),
                    promo.map { Msg.Raw(it) },
                ),
            )
        }
        // Emoji are the title's problem only: descriptions may use them.
        if (strict && emoji(text)) add(Check(Status.Fail, str(R.string.aso_emoji, field), str(R.string.aso_emoji_d)))
        if (shouting(text)) add(Check(Status.Warn, str(R.string.aso_caps, field), str(R.string.aso_caps_d)))
        if (NOISE.containsMatchIn(text)) add(Check(Status.Warn, str(R.string.aso_noise, field), str(R.string.aso_noise_d)))
    }

    private val TITLE = Msg.Str(R.string.aso_f_title)
    private val SHORT = Msg.Str(R.string.aso_f_short)
    private val FULL = Msg.Str(R.string.aso_f_full)

    fun title(l: Listing): List<Check> = buildList {
        add(lengthCheck(l.title, TITLE_MAX, TITLE))
        if (l.title.isBlank()) return@buildList
        addAll(policy(l.title, TITLE, strict = true))
        val used = length(l.title.trim())
        if (used <= TITLE_MAX - 10) {
            add(Check(Status.Info, str(R.string.aso_title_room, num(TITLE_MAX - used)), str(R.string.aso_title_room_d)))
        }
        val repeated = words(l.title).groupingBy { it }.eachCount().filter { it.value > 1 && it.key.length > 2 }.keys
        if (repeated.isNotEmpty()) {
            add(Check(Status.Warn, str(R.string.aso_title_repeat), str(R.string.aso_title_repeat_d), repeated.map { Msg.Raw(it) }))
        }
        keywordIn(l, l.title, TITLE)?.let { add(it) }
    }

    fun short(l: Listing): List<Check> = buildList {
        add(lengthCheck(l.short, SHORT_MAX, SHORT))
        if (l.short.isBlank()) return@buildList
        addAll(policy(l.short, SHORT, strict = false))
        if (length(l.short.trim()) < SHORT_MAX / 2) {
            add(Check(Status.Info, str(R.string.aso_short_brief), str(R.string.aso_short_brief_d)))
        }
        if (l.title.isNotBlank() && fold(l.short.trim()).startsWith(fold(l.title.trim()))) {
            add(Check(Status.Warn, str(R.string.aso_short_echo), str(R.string.aso_short_echo_d)))
        }
        keywordIn(l, l.short, SHORT)?.let { add(it) }
    }

    fun full(l: Listing): List<Check> = buildList {
        add(lengthCheck(l.full, FULL_MAX, FULL))
        if (l.full.isBlank()) return@buildList
        val n = length(l.full.trim())
        if (n < FULL_THIN) add(Check(Status.Warn, str(R.string.aso_full_thin, num(n)), str(R.string.aso_full_thin_d)))
        addAll(policy(l.full, FULL, strict = false))
        val paragraphs = l.full.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        val lines = l.full.lines().count { it.isNotBlank() }
        if (n > FULL_THIN && paragraphs < 2 && lines < 4) {
            add(Check(Status.Warn, str(R.string.aso_full_wall), str(R.string.aso_full_wall_d)))
        } else if (n > FULL_THIN) {
            add(Check(Status.Pass, str(R.string.aso_full_structured)))
        }
        if (Regex("https?://|www\\.").containsMatchIn(l.full)) {
            add(Check(Status.Info, str(R.string.aso_full_links), str(R.string.aso_full_links_d)))
        }
        val stuffed = placements(l).filter { it.density > DENSITY_HIGH }
        if (stuffed.isNotEmpty()) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.aso_stuffed),
                    str(R.string.aso_stuffed_d),
                    stuffed.map { Msg.Raw("${it.keyword} · ${"%.1f".format(Locale.ROOT, it.density)}%") },
                ),
            )
        }
        val first = l.keywords.firstOrNull()
        if (first != null) {
            add(
                if (count(l.full.take(FOLD), first) > 0) {
                    Check(Status.Pass, str(R.string.aso_fold_ok, Msg.Raw(first)))
                } else {
                    Check(Status.Warn, str(R.string.aso_fold_missing, Msg.Raw(first)), str(R.string.aso_fold_missing_d))
                },
            )
        }
    }

    /** Whether the main keyword is in a field that is short enough for every word to count. */
    private fun keywordIn(l: Listing, text: String, field: Msg): Check? {
        val first = l.keywords.firstOrNull() ?: return null
        return if (count(text, first) > 0) {
            Check(Status.Pass, str(R.string.aso_kw_in, Msg.Raw(first), field))
        } else {
            Check(Status.Warn, str(R.string.aso_kw_not_in, Msg.Raw(first), field), str(R.string.aso_kw_not_in_d))
        }
    }

    fun placements(l: Listing): List<Placement> {
        val total = words(l.full).size
        return l.keywords.map { k ->
            val inFull = count(l.full, k)
            Placement(
                keyword = k,
                inTitle = count(l.title, k) > 0,
                inShort = count(l.short, k) > 0,
                aboveFold = count(l.full.take(FOLD), k) > 0,
                inFull = inFull,
                density = if (total == 0) 0.0 else inFull * words(k).size * 100.0 / total,
            )
        }
    }

    /** Keywords that appear anywhere, as a percentage of those listed. */
    fun coverage(l: Listing): Int {
        if (l.keywords.isEmpty()) return 0
        return placements(l).count { it.anywhere } * 100 / l.keywords.size
    }

    fun coverageChecks(l: Listing): List<Check> = buildList {
        if (l.keywords.isEmpty()) {
            add(Check(Status.Info, str(R.string.aso_no_keywords), str(R.string.aso_no_keywords_d)))
            return@buildList
        }
        val p = placements(l)
        val missing = p.filterNot { it.anywhere }
        add(
            if (missing.isEmpty()) {
                Check(Status.Pass, str(R.string.aso_cov_all, num(p.size)))
            } else {
                Check(
                    Status.Warn,
                    str(R.string.aso_cov_missing, num(missing.size), num(p.size)),
                    str(R.string.aso_cov_missing_d),
                    missing.map { Msg.Raw(it.keyword) },
                )
            },
        )
        val onlyFull = p.filter { it.anywhere && !it.inTitle && !it.inShort }
        if (onlyFull.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.aso_cov_deep), str(R.string.aso_cov_deep_d), onlyFull.map { Msg.Raw(it.keyword) }))
        }
    }

    /**
     * What to change, most valuable first: the title is weighted most by
     * search, then the short description, then the start of the full one.
     */
    fun suggestions(l: Listing): List<Check> = buildList {
        val p = placements(l)
        val titleRoom = TITLE_MAX - length(l.title.trim())
        val first = p.firstOrNull()
        if (first != null && !first.inTitle) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.aso_sug_title_kw, Msg.Raw(first.keyword)),
                    str(R.string.aso_sug_title_kw_d, num(titleRoom.coerceAtLeast(0))),
                ),
            )
        }
        p.drop(1).filter { !it.inTitle && !it.inShort }.take(3).forEach {
            add(Check(Status.Info, str(R.string.aso_sug_short_kw, Msg.Raw(it.keyword))))
        }
        p.filter { it.anywhere && !it.aboveFold && it.inFull == 0 }.take(3).forEach {
            add(Check(Status.Info, str(R.string.aso_sug_full_kw, Msg.Raw(it.keyword))))
        }
        // Words the title and short description both spend space on.
        val shared = words(l.title).toSet().intersect(words(l.short).toSet()).filter { it.length > 3 }
        if (shared.isNotEmpty()) {
            add(Check(Status.Info, str(R.string.aso_sug_shared), str(R.string.aso_sug_shared_d), shared.map { Msg.Raw(it) }))
        }
        if (isEmpty()) add(Check(Status.Pass, str(R.string.aso_sug_none)))
    }

    /**
     * A score out of 100 made of parts a developer can see and move. Not a
     * prediction of rank, which depends on installs, ratings and Google.
     */
    fun score(l: Listing): List<ScorePart> {
        fun fieldScore(checks: List<Check>, max: Int): Int {
            if (checks.any { it.status == Status.Fail }) return 0
            val warns = checks.count { it.status == Status.Warn }
            return (max - warns * max / 4).coerceAtLeast(0)
        }
        val p = placements(l)
        val keywords = when {
            l.keywords.isEmpty() -> 0
            else -> {
                val covered = coverage(l) * 10 / 100
                val titled = if (p.first().inTitle) 6 else 0
                val folded = if (p.first().aboveFold) 4 else 0
                covered + titled + folded
            }
        }
        return listOf(
            ScorePart(R.string.aso_f_title, fieldScore(title(l), 25), 25),
            ScorePart(R.string.aso_f_short, fieldScore(short(l), 20), 20),
            ScorePart(R.string.aso_f_full, fieldScore(full(l), 25), 25),
            ScorePart(R.string.aso_part_keywords, keywords, 20),
            ScorePart(
                R.string.aso_part_policy,
                if (promo(l.title).isNotEmpty() || emoji(l.title) || listOf(l.short, l.full).any { promo(it, title = false).isNotEmpty() }) 0 else 10,
                10,
            ),
        )
    }

    fun total(parts: List<ScorePart>): Int = parts.sumOf { it.points }

    /**
     * The Store Listing Checklist: what Play Console asks for before a
     * listing can be published, with the text parts checked and the rest
     * named, because those are forms in Console that no file can fill.
     */
    fun checklist(l: Listing): List<Check> = buildList {
        add(lengthCheck(l.title, TITLE_MAX, TITLE))
        add(lengthCheck(l.short, SHORT_MAX, SHORT))
        add(lengthCheck(l.full, FULL_MAX, FULL))
        val policy = listOf(TITLE to l.title, SHORT to l.short, FULL to l.full)
            .flatMap { (field, text) -> policy(text, field, strict = field == TITLE) }
        addAll(policy.filter { it.status == Status.Fail })
        listOf(
            R.string.aso_cl_icon, R.string.aso_cl_feature, R.string.aso_cl_shots, R.string.aso_cl_category,
            R.string.aso_cl_contact, R.string.aso_cl_privacy, R.string.aso_cl_rating, R.string.aso_cl_safety,
            R.string.aso_cl_audience, R.string.aso_cl_ads,
        ).forEach { add(Check(Status.Info, str(it))) }
    }

    /** Listing Quality Check: everything that reads badly, across all three fields. */
    fun quality(l: Listing): List<Check> =
        (title(l) + short(l) + full(l)).filter { it.status != Status.Pass }.ifEmpty {
            listOf(Check(Status.Pass, str(R.string.aso_quality_ok)))
        }
}
