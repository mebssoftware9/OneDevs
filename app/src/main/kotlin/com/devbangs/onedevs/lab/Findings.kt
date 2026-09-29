package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * How much a finding matters.
 *
 * [Blocking] means Play or the device will refuse this build. [Worth] means it
 * costs users something real -- bytes, privacy, security -- and a developer
 * would want to know before shipping. [Note] is context that changes how the
 * rest of the report should be read, not a problem.
 */
enum class Severity { Blocking, Worth, Note }

/**
 * One thing worth saying about an APK.
 *
 * Every field exists because a report of bare facts is a file viewer. [what]
 * is the observation, [why] is what it costs, and [action] is the next thing
 * to do -- and a finding without all three does not get made.
 *
 * All three are [Msg] rather than String: the rules choose a sentence, the
 * screen renders it in the reader's language.
 */
data class Finding(
    val severity: Severity,
    val what: Msg,
    val why: Msg,
    val action: Msg,
    val evidence: List<Msg> = emptyList(),
)

/**
 * Turns a parse into judgements.
 *
 * Deliberately not a method on ApkReport: the report is what the file says and
 * this is what OneDevs thinks about it, and the two want to be argued with
 * separately. Every rule is a pure function of the report, so every rule is
 * testable without an APK or a device.
 */
object Findings {

    /**
     * x86 and x86_64 exist for emulators. Google Play has never served them to
     * a phone, so on a released app they are bytes every user downloads and
     * nobody executes.
     */
    private val EMULATOR_ABIS = setOf("x86", "x86_64")

    /** Above this, native multidex handles it and nothing is wrong. */
    private const val MULTIDEX_NATIVE_FROM = 21

    /**
     * Bytes of DEX per method, above which the file is carrying something
     * other than code.
     *
     * Measured rather than guessed. A normal build sits near 250 bytes per
     * method -- OneDevs itself is 29.1 MB across 120,636, which is 253. The
     * file that prompted this was 61.1 MB across 106, which is 604,415, about
     * two thousand times heavier. Twenty thousand is eighty times a normal
     * build and thirty times below the case it catches, which is as much room
     * as a threshold like this can ask for.
     */
    private const val DEX_BYTES_PER_METHOD_CEILING = 20_000L

    fun of(report: ApkReport): List<Finding> = buildList {
        packed(report)?.let(::add)
        debug(report)?.let(::add)
        targetSdk(report)?.let(::add)
        unsigned(report)?.let(::add)
        emulatorAbis(report)?.let(::add)
        cleartext(report)?.let(::add)
        dangerousPermissions(report)?.let(::add)
        exportedComponents(report)?.let(::add)
        backup(report)?.let(::add)
    }.sortedBy { it.severity.ordinal }

    /**
     * Entries or bytes that are not the code they claim to be.
     *
     * Three shapes, one conclusion: nothing parses, some entries are not DEX,
     * or one enormous DEX declares almost nothing. Each gets its own reason,
     * because a card reading "590 KB per method" once explained itself with
     * "Android will not load these directly", where "these" referred to
     * nothing.
     *
     * Not a verdict on the app. Packers are used by legitimate apps against
     * cloning and by malware against analysis. What it means is that the
     * artifact does not contain the code that will run.
     */
    private fun packed(r: ApkReport): Finding? {
        val unreadable = r.dexEntries - r.dex.files
        val perMethod = bytesPerMethod(r)
        if (unreadable <= 0 && perMethod <= DEX_BYTES_PER_METHOD_CEILING) return null
        val dexBytes = r.sizes.firstOrNull { it.label == "DEX" }?.bytes ?: 0L
        val (what, why) = when {
            r.dex.files == 0 && r.dexEntries > 0 ->
                str(R.string.f_packed_none_what, r.dexEntries) to
                    str(R.string.f_packed_none_why)
            unreadable > 0 ->
                str(R.string.f_packed_mixed_what, unreadable, r.dexEntries) to
                    str(R.string.f_packed_mixed_why)
            else ->
                plural(
                    R.plurals.f_packed_huge_what, r.dex.methods,
                    dexBytes.readableBytes(), r.dex.methods,
                ) to
                    str(R.string.f_packed_huge_why)
        }
        return Finding(
            severity = Severity.Worth,
            what = what,
            why = why,
            action = str(R.string.f_packed_action),
            evidence = buildList {
                // Only worth saying when the counts actually disagree.
                if (unreadable > 0) add(str(R.string.ev_readable, r.dex.files, r.dexEntries))
                if (perMethod > 0) add(str(R.string.ev_per_method, perMethod.readableBytes()))
            },
        )
    }

    /**
     * A debug build is not a finding about the app, it is a finding about
     * which file was picked. Saying "Play rejects this" about an artifact
     * nobody was going to upload is noise; saying "you analysed the wrong
     * file" is the useful sentence.
     */
    private fun debug(r: ApkReport) = if (!r.debuggable) {
        null
    } else {
        Finding(
            severity = Severity.Note,
            what = str(R.string.f_debug_what),
            why = str(R.string.f_debug_why),
            action = str(R.string.f_debug_action),
        )
    }

    private fun targetSdk(r: ApkReport) = if (r.meetsPlayTargetFloor) {
        null
    } else {
        Finding(
            severity = Severity.Blocking,
            what = str(R.string.f_target_what, r.targetSdk, ApkReport.PLAY_TARGET_SDK_FLOOR),
            why = str(R.string.f_target_why),
            action = str(R.string.f_target_action, ApkReport.PLAY_TARGET_SDK_FLOOR),
        )
    }

    private fun unsigned(r: ApkReport) = if (r.signatureSha256 != null) {
        null
    } else {
        Finding(
            severity = Severity.Blocking,
            what = str(R.string.f_unsigned_what),
            why = str(R.string.f_unsigned_why),
            action = str(R.string.f_unsigned_action),
        )
    }

    /**
     * The one in the screenshot that nobody says out loud. Two of four ABIs
     * in a typical release exist only for emulators.
     */
    private fun emulatorAbis(r: ApkReport): Finding? {
        val emulator = r.abis.filter { it in EMULATOR_ABIS }
        if (emulator.isEmpty() || r.abis.size == emulator.size) return null
        val bytes = r.sizes.firstOrNull { it.label == "Native libraries" }?.bytes ?: 0L
        val share = if (r.abis.isEmpty()) 0L else bytes * emulator.size / r.abis.size
        return Finding(
            severity = Severity.Worth,
            what = str(R.string.f_abi_what, emulator.joinToString(", ")),
            why = if (share > 0) {
                str(R.string.f_abi_why_size, share.readableBytes())
            } else {
                str(R.string.f_abi_why)
            },
            action = str(R.string.f_abi_action),
            evidence = r.abis.map { Msg.Raw(it) },
        )
    }

    private fun cleartext(r: ApkReport) = if (!r.allowsCleartext) {
        null
    } else {
        Finding(
            severity = Severity.Worth,
            what = str(R.string.f_cleartext_what),
            why = str(R.string.f_cleartext_why),
            action = str(R.string.f_cleartext_action),
        )
    }

    /** Two dangerous permissions is not a finding. Which two is. */
    private fun dangerousPermissions(r: ApkReport) = if (r.dangerousPermissions.isEmpty()) {
        null
    } else {
        Finding(
            severity = Severity.Note,
            what = plural(
                R.plurals.f_perms_what, r.dangerousPermissions.size, r.dangerousPermissions.size,
            ),
            why = str(R.string.f_perms_why),
            action = str(R.string.f_perms_action),
            evidence = r.dangerousPermissions.map { Msg.Raw(it.substringAfterLast('.')) },
        )
    }

    /**
     * An exported component is an entry point any other app on the device can
     * invoke. The launcher activity has to be one. The rest are worth a look.
     */
    private fun exportedComponents(r: ApkReport): Finding? {
        val exported = r.components.exported
        if (exported.size <= 1) return null
        return Finding(
            severity = Severity.Worth,
            what = plural(R.plurals.f_exported_what, exported.size, exported.size),
            why = str(R.string.f_exported_why),
            action = str(R.string.f_exported_action),
            evidence = exported.map { Msg.Raw(it.substringAfterLast('.')) },
        )
    }

    private fun backup(r: ApkReport) = if (!r.allowsBackup) {
        null
    } else {
        Finding(
            severity = Severity.Note,
            what = str(R.string.f_backup_what),
            why = str(R.string.f_backup_why),
            action = str(R.string.f_backup_action),
        )
    }

    /**
     * How heavy the DEX is for the code it declares.
     *
     * A packer leaves a header the platform will accept and hides the payload
     * behind it, so the counts stay small while the file does not. Zero when
     * there is nothing to divide, which is not a signal either way.
     */
    private fun bytesPerMethod(r: ApkReport): Long {
        if (r.dex.methods <= 0) return 0
        val bytes = r.sizes.firstOrNull { it.label == "DEX" }?.bytes ?: return 0
        return bytes / r.dex.methods
    }

    /**
     * Whether the method count describes the app or a loader. The report uses
     * this to withhold a number that would otherwise look like an answer.
     */
    fun methodCountIsMeaningful(r: ApkReport): Boolean =
        r.dexEntries == r.dex.files && bytesPerMethod(r) <= DEX_BYTES_PER_METHOD_CEILING

    /**
     * Whether multidex is worth mentioning at all.
     *
     * The 65,536 ceiling mattered when crossing it meant adding a library and
     * an Application subclass. From API 21 the platform loads multiple DEX
     * files natively, so on any modern minSdk it is a number, not a problem --
     * and a tool that flags it is a tool people learn to scroll past.
     */
    fun multidexWorthMentioning(r: ApkReport): Boolean =
        r.dex.overSingleDexLimit && r.minSdk < MULTIDEX_NATIVE_FROM
}

/** Bytes as a developer would say them, for use inside finding text. */
internal fun Long.readableBytes(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024; unit++
    }
    return if (unit == 0) "$this B" else "%.1f %s".format(value, units[unit])
}
