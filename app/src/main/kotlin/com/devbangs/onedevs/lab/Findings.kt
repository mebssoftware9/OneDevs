package com.devbangs.onedevs.lab

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
 * Every field here exists because a report of bare facts is a file viewer.
 * [what] is the observation, [why] is what it costs, and [action] is the next
 * thing to do about it -- and a finding without all three does not get made.
 * "120,710 methods" is a fact; "past the single-DEX ceiling" is a fact with a
 * scary noun attached; neither is worth a line unless something follows.
 *
 * [evidence] is the specific thing observed, so nobody has to take the
 * finding's word for it: the permission's own name, the ABIs, the bytes.
 */
data class Finding(
    val severity: Severity,
    val what: String,
    val why: String,
    val action: String,
    val evidence: List<String> = emptyList(),
)

/**
 * Turns a parse into judgements.
 *
 * Deliberately not a method on ApkReport: the report is what the file says and
 * this is what OneDevs thinks about it, and the two want to be argued with
 * separately. Every rule here is a pure function of the report, so every rule
 * here is testable without an APK.
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
     * file that prompted this was 61.1 MB across 106, which is 604,415, or
     * about two thousand times heavier. Twenty thousand is eighty times a
     * normal build and thirty times below the case it catches, which is as
     * much room as a threshold like this can ask for.
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
            what = "This is a debug build.",
            why = "Debug builds are signed with a debug key, ship unminified, " +
                "and are rejected by Play. Findings below describe this file, " +
                "not the one you would upload.",
            action = "Analyse the release artifact instead: " +
                "app/build/outputs/bundle/release or the AAB from your CI.",
        )
    }

    /**
     * Entries named .dex that are not DEX files.
     *
     * The signature of a packer: a small real classes.dex that loads
     * everything else at runtime from encrypted blobs stored under .dex names.
     * It showed up as a 61 MB DEX slice reporting 106 methods -- two numbers
     * from two code paths, one counting entries and one counting headers, and
     * their disagreement is the finding.
     *
     * Not a verdict on the app. Packers are used by legitimate apps against
     * cloning, and by malware against analysis. What it does mean is that the
     * artifact does not contain the code that will run, so nothing else in
     * this report describes the real application.
     */
    private fun packed(r: ApkReport): Finding? {
        val unreadable = r.dexEntries - r.dex.files
        val perMethod = bytesPerMethod(r)
        val bloated = perMethod > DEX_BYTES_PER_METHOD_CEILING
        if (unreadable <= 0 && !bloated) return null

        // One conclusion, three reasons. Merging the shapes into a single
        // finding was right; leaving them one explanation was not, and it
        // showed: a card reading "the DEX is 590 KB per method" went on to say
        // "Android will not load these directly", where "these" referred to
        // nothing. The reason has to match the shape that produced it.
        val dexBytes = r.sizes.firstOrNull { it.label == "DEX" }?.bytes ?: 0L
        val (what, why) = when {
            r.dex.files == 0 && r.dexEntries > 0 -> Pair(
                "No readable DEX in ${r.dexEntries} .dex entries.",
                "Not one of them starts with a DEX header, so there is no code " +
                    "in this artifact that Android could run. Whatever executes " +
                    "is produced at runtime.",
            )
            unreadable > 0 -> Pair(
                "$unreadable of ${r.dexEntries} .dex entries are not DEX files.",
                "Android will not load those directly, so they are payloads " +
                    "decrypted at runtime by the ${r.dex.files} that it will. " +
                    "The code that runs is not in this file.",
            )
            else -> Pair(
                "${dexBytes.readableBytes()} of DEX declaring ${r.dex.methods} methods.",
                "The header is real and nearly empty. A build this size " +
                    "normally declares hundreds of thousands of methods, so the " +
                    "bulk of the file is data sitting behind a valid header, " +
                    "unpacked at runtime.",
            )
        }
        return Finding(
            severity = Severity.Worth,
            what = what,
            why = why + " This is what a packer looks like, and it means every " +
                "other finding here describes the loader rather than the app.",
            action = "If this is your build, check what your shrinker or " +
                "protection tool is producing. If it is not, treat every other " +
                "finding here as describing a stub.",
            evidence = buildList {
                // Only worth saying when the counts actually disagree.
                if (unreadable > 0) add("${r.dex.files} readable of ${r.dexEntries}")
                if (perMethod > 0) add("${perMethod.readableBytes()} per method")
            },
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

    private fun targetSdk(r: ApkReport) = if (r.meetsPlayTargetFloor) {
        null
    } else {
        Finding(
            severity = Severity.Blocking,
            what = "Targets API ${r.targetSdk}; Play requires ${ApkReport.PLAY_TARGET_SDK_FLOOR}.",
            why = "Play refuses new apps and updates below the floor, which " +
                "rises every August. This build cannot be uploaded.",
            action = "Raise targetSdk to ${ApkReport.PLAY_TARGET_SDK_FLOOR} in " +
                "build.gradle.kts, then retest: each level brings behaviour " +
                "changes that only appear at runtime.",
        )
    }

    private fun unsigned(r: ApkReport) = if (r.signatureSha256 != null) {
        null
    } else {
        Finding(
            severity = Severity.Blocking,
            what = "No signature Android could read.",
            why = "An unsigned package cannot be installed or uploaded.",
            action = "Build a signed artifact, or check the signing config " +
                "actually applied to this variant.",
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
            what = "Ships ${emulator.joinToString(" and ")} native libraries.",
            why = "Play has never served an x86 build to a phone. These are " +
                "bytes in every install that no user runs" +
                if (share > 0) ", roughly ${share.readableBytes()} here." else ".",
            action = "An App Bundle splits by ABI automatically and the problem " +
                "disappears. For an APK, add an abiFilters block for " +
                "arm64-v8a and armeabi-v7a.",
            evidence = r.abis,
        )
    }

    private fun cleartext(r: ApkReport) = if (!r.allowsCleartext) {
        null
    } else {
        Finding(
            severity = Severity.Worth,
            what = "Allows cleartext HTTP.",
            why = "Any request this app makes over http:// can be read and " +
                "altered on the network the user is on.",
            action = "Set android:usesCleartextTraffic=\"false\", or add a " +
                "network security config that permits only the hosts that " +
                "genuinely need it.",
        )
    }

    /** Two dangerous permissions is not a finding. Which two is. */
    private fun dangerousPermissions(r: ApkReport) = if (r.dangerousPermissions.isEmpty()) {
        null
    } else {
        Finding(
            severity = Severity.Note,
            what = "Asks for ${r.dangerousPermissions.size} runtime " +
                if (r.dangerousPermissions.size == 1) "permission." else "permissions.",
            why = "Each one is a dialog the user can refuse, and each needs a " +
                "Data Safety entry in the Play listing.",
            action = "Check every one is still used. A permission left behind " +
                "by a removed feature costs installs and answers in the " +
                "Data Safety form.",
            evidence = r.dangerousPermissions.map { it.substringAfterLast('.') },
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
            what = "${exported.size} components are exported.",
            why = "Any app on the device can start an exported component. One " +
                "of these is the launcher activity and has to be; the others " +
                "are entry points into this app that you may not have meant " +
                "to open.",
            action = "For each, confirm android:exported=\"true\" is deliberate " +
                "and the component validates what it receives.",
            evidence = exported.map { it.substringAfterLast('.') },
        )
    }

    private fun backup(r: ApkReport) = if (!r.allowsBackup) {
        null
    } else {
        Finding(
            severity = Severity.Note,
            what = "Backup is allowed.",
            why = "App data is copied to the user's cloud backup and restored " +
                "onto new devices, including anything cached that should not " +
                "travel.",
            action = "Check the backup rules exclude tokens, keys and caches, " +
                "or set android:allowBackup=\"false\".",
        )
    }

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
