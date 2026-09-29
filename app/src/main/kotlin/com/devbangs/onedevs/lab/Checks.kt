package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * How a check came out.
 *
 * Declared mildest first, so sorting by [ordinal] descending puts what needs
 * doing at the top.
 */
enum class Status { Pass, Info, Warn, Fail }

/**
 * One question a tool asks of an APK, and its answer.
 *
 * A [Finding] explains a problem at length; a check is a line on a list, and
 * it has a passing form as well as a failing one. A checklist that only shows
 * failures cannot tell "passed" from "never checked".
 */
data class Check(
    val status: Status,
    val title: Msg,
    val detail: Msg? = null,
    val evidence: List<Msg> = emptyList(),
)

/** What a list of checks adds up to. */
enum class Verdict { Ready, Warnings, Blocked }

fun List<Check>.verdict(): Verdict = when {
    any { it.status == Status.Fail } -> Verdict.Blocked
    any { it.status == Status.Warn } -> Verdict.Warnings
    else -> Verdict.Ready
}

/** Most urgent first, keeping each tool's own order within a status. */
fun List<Check>.urgentFirst(): List<Check> = sortedByDescending { it.status.ordinal }

/**
 * The rules behind every Lab tool that is not the APK Analyzer itself.
 *
 * Each function is one tool's questions, and each is a pure function of the
 * report -- plus the device, for the testing layer -- so all of them run in a
 * unit test. Where a finding already says something, the check reuses its
 * sentence rather than inventing a second way to say it.
 */
object Checks {

    /**
     * Play App Signing asks for a key valid until at least this date:
     * 22 October 2033, 00:00 UTC.
     */
    const val PLAY_KEY_VALID_UNTIL = 2_013_552_000_000L

    /** Play's ceiling on versionCode. */
    const val MAX_VERSION_CODE = 2_100_000_000L

    private val PRE_RELEASE = Regex("(?i)(debug|snapshot|alpha|beta|-rc|dev|test)")
    private const val P = "android.permission."
    private val DENSITIES = listOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    // ---- Shared questions ------------------------------------------------

    private fun signed(r: ApkReport) = if (r.signatureSha256 != null) {
        null
    } else {
        Check(Status.Fail, str(R.string.f_unsigned_what), str(R.string.f_unsigned_why))
    }

    private fun certificate(r: ApkReport): Check? = when {
        r.signatureSha256 == null || r.certificates.isEmpty() -> null
        r.debugCertificate -> Check(Status.Fail, str(R.string.ck_debug_cert), str(R.string.ck_debug_cert_d))
        else -> Check(Status.Pass, str(R.string.ck_release_cert))
    }

    private fun scheme(r: ApkReport): Check? = when {
        // No block and no JAR signature found, yet Android read a signer:
        // the layout could not be read, so say nothing rather than guess.
        !r.schemes.any -> null
        r.schemes.modern -> Check(Status.Pass, str(R.string.ck_v2_ok), evidence = schemeNames(r.schemes))
        else -> Check(
            if (r.targetSdk >= 30) Status.Fail else Status.Warn,
            str(R.string.ck_v1_only),
            str(R.string.ck_v1_only_d),
        )
    }

    fun schemeNames(s: SigningSchemes): List<Msg> = buildList {
        if (s.v1) add(Msg.Raw("v1"))
        if (s.v2) add(Msg.Raw("v2"))
        if (s.v3) add(Msg.Raw("v3"))
        if (s.v31) add(Msg.Raw("v3.1"))
    }

    private fun expiry(r: ApkReport): Check? {
        val soonest = r.certificates.minByOrNull { it.notAfter } ?: return null
        return if (soonest.notAfter < PLAY_KEY_VALID_UNTIL) {
            Check(Status.Warn, str(R.string.ck_cert_expiry, Msg.Date(soonest.notAfter)), str(R.string.ck_cert_expiry_d))
        } else {
            Check(Status.Pass, str(R.string.ck_cert_valid, Msg.Date(soonest.notAfter)))
        }
    }

    private fun debuggable(r: ApkReport) = if (r.debuggable) {
        Check(Status.Fail, str(R.string.ck_debuggable), str(R.string.ck_debuggable_d))
    } else {
        Check(Status.Pass, str(R.string.ck_not_debuggable))
    }

    private fun testOnly(r: ApkReport) = if (r.testOnly) {
        Check(Status.Fail, str(R.string.ck_test_only), str(R.string.ck_test_only_d))
    } else {
        Check(Status.Pass, str(R.string.ck_not_test_only))
    }

    private fun target(r: ApkReport) = if (r.meetsPlayTargetFloor) {
        Check(Status.Pass, str(R.string.ck_target_ok, num(ApkReport.PLAY_TARGET_SDK_FLOOR)))
    } else {
        Check(
            Status.Fail,
            str(R.string.f_target_what, r.targetSdk, ApkReport.PLAY_TARGET_SDK_FLOOR),
            str(R.string.f_target_why),
        )
    }

    private fun r8(r: ApkReport): Check {
        val marker = r.compiler
        return when {
            marker == null -> Check(Status.Warn, str(R.string.ck_r8_unknown), str(R.string.ck_r8_unknown_d))
            marker.shrunk -> Check(
                Status.Pass,
                str(R.string.ck_r8_on),
                evidence = listOfNotNull(marker.version?.let { Msg.Raw("R8 $it") }),
            )
            else -> Check(Status.Warn, str(R.string.ck_r8_off), str(R.string.ck_r8_off_d))
        }
    }

    private fun dexMode(r: ApkReport): Check? = when (r.compiler?.mode) {
        "debug" -> Check(Status.Fail, str(R.string.ck_dex_debug), str(R.string.ck_dex_debug_d))
        "release" -> Check(Status.Pass, str(R.string.ck_dex_release))
        else -> null
    }

    private fun cleartext(r: ApkReport) = if (r.allowsCleartext) {
        Check(Status.Warn, str(R.string.f_cleartext_what), str(R.string.f_cleartext_why))
    } else {
        Check(Status.Pass, str(R.string.ck_cleartext_off))
    }

    private fun versionCode(r: ApkReport) = if (r.versionCode > MAX_VERSION_CODE) {
        Check(Status.Fail, str(R.string.ck_version_high), str(R.string.ck_version_high_d))
    } else {
        Check(Status.Pass, str(R.string.ck_version_ok, num(r.versionCode)))
    }

    /** Exported, not the way in from the launcher, and guarded by nothing. */
    fun openComponents(r: ApkReport): List<Component> = r.componentList.filter {
        it.exported && it.permission == null && it.name !in r.manifest.launchers
    }

    private fun exported(r: ApkReport): Check? {
        if (r.componentList.isEmpty()) return null
        val open = openComponents(r)
        return if (open.isEmpty()) {
            Check(Status.Pass, str(R.string.ck_exported_ok))
        } else {
            Check(
                Status.Warn,
                str(R.string.ck_exported_open),
                str(R.string.f_exported_action),
                open.map { Msg.Raw(it.name.substringAfterLast('.')) },
            )
        }
    }

    /** 32-bit ABIs shipped without the 64-bit one a modern phone would pick. */
    fun missing64(r: ApkReport): List<String> {
        val abis = r.natives.map { it.abi }.toSet()
        return abis.filter { abi -> NativeLib.PAIR[abi]?.let { it !in abis } == true }.sorted()
    }

    private fun sixtyFourBit(r: ApkReport): Check? {
        if (r.natives.isEmpty()) return null
        val missing = missing64(r)
        return if (missing.isEmpty()) {
            Check(Status.Pass, str(R.string.ck_64_ok))
        } else {
            Check(Status.Fail, str(R.string.ck_64_missing), str(R.string.ck_64_missing_d), missing.map { Msg.Raw(it) })
        }
    }

    /**
     * 16 KB pages, both halves: the ELF segments and, for libraries stored
     * uncompressed, where their bytes sit in the ZIP.
     *
     * Play requires it of updates targeting Android 15 and later, so below
     * that it is a warning about the next targetSdk bump, not a refusal.
     */
    fun sixteenK(r: ApkReport): List<Check> {
        val libs = r.natives.filter { it.is64 }
        if (libs.isEmpty()) return emptyList()
        val severity = if (r.targetSdk >= 35) Status.Fail else Status.Warn
        val elfBad = libs.filter { it.elf16k == false }
        val zipLibs = libs.filter { it.zip16k != null }
        val zipBad = zipLibs.filter { it.zip16k == false }
        return buildList {
            add(
                if (elfBad.isEmpty()) {
                    Check(Status.Pass, str(R.string.ck_16k_elf_ok))
                } else {
                    Check(
                        severity,
                        str(R.string.ck_16k_elf_bad, num(elfBad.size), num(libs.size)),
                        str(R.string.ck_16k_elf_bad_d),
                        elfBad.map { Msg.Raw(it.name) }.distinct(),
                    )
                },
            )
            if (zipLibs.isNotEmpty()) {
                add(
                    if (zipBad.isEmpty()) {
                        Check(Status.Pass, str(R.string.ck_16k_zip_ok))
                    } else {
                        Check(
                            severity,
                            str(R.string.ck_16k_zip_bad, num(zipBad.size), num(zipLibs.size)),
                            str(R.string.ck_16k_zip_bad_d),
                            zipBad.map { Msg.Raw(it.name) }.distinct(),
                        )
                    },
                )
            }
        }
    }

    private fun unruledBackup(r: ApkReport): Boolean =
        r.allowsBackup && !r.manifest.dataExtractionRules && r.manifest.fullBackupContent == BackupRules.Absent

    // ---- Layer 2: what was built -----------------------------------------

    fun signing(r: ApkReport): List<Check> =
        listOfNotNull(signed(r), certificate(r), scheme(r), expiry(r))

    fun natives(r: ApkReport): List<Check> = buildList {
        sixtyFourBit(r)?.let(::add)
        addAll(sixteenK(r))
        if (r.natives.isNotEmpty() && r.natives.none { it.stored }) {
            add(Check(Status.Info, str(R.string.ck_libs_extracted), str(R.string.ck_libs_extracted_d)))
        }
    }

    fun components(r: ApkReport): List<Check> = listOfNotNull(exported(r))

    fun sdk(r: ApkReport, d: DeviceProfile?): List<Check> = buildList {
        add(target(r))
        add(
            if (r.targetSdk >= Platform.LATEST) {
                Check(Status.Pass, str(R.string.ck_target_latest, num(Platform.LATEST)))
            } else {
                Check(Status.Info, str(R.string.ck_target_behind, num(Platform.LATEST)))
            },
        )
        if (d != null) {
            add(deviceApi(r, d))
            if (d.api > r.targetSdk) {
                add(Check(Status.Info, str(R.string.ck_compat_mode), str(R.string.ck_compat_mode_d)))
            }
        }
    }

    /**
     * Resources whose path has lost its type folder -- res/a1.xml rather than
     * res/layout/main.xml -- are what resource optimisation leaves behind.
     */
    fun resourcesShortened(r: ApkReport): Boolean {
        val res = r.items.filter { it.name.startsWith("res/") }
        if (res.isEmpty()) return false
        return res.count { it.name.count { c -> c == '/' } == 1 } * 2 > res.size
    }

    /** Density qualifiers present among resource folders. */
    fun densities(r: ApkReport): Set<String> = r.items.asSequence()
        .filter { it.name.startsWith("res/") }
        .mapNotNull { it.name.split('/').getOrNull(1) }
        .flatMap { it.split('-').asSequence() }
        .filter { it in DENSITIES || it == "anydpi" || it == "nodpi" }
        .toSet()

    fun size(r: ApkReport): List<Check> = buildList {
        val emulator = r.abis.filter { it == "x86" || it == "x86_64" }
        if (emulator.isNotEmpty() && emulator.size < r.abis.size) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.f_abi_what, emulator.joinToString(", ")),
                    str(R.string.f_abi_why),
                ),
            )
        }
        if (r.compiler != null && !r.compiler.shrunk) {
            add(Check(Status.Warn, str(R.string.ck_r8_off), str(R.string.ck_r8_off_d)))
        }
        val png = r.items.filter {
            it.name.startsWith("res/") && it.name.endsWith(".png") && !it.name.endsWith(".9.png")
        }.sumOf { it.compressedSize }
        if (png > PNG_WORTH_MENTIONING) {
            add(Check(Status.Info, str(R.string.ck_png, Msg.Raw(png.readableBytes())), str(R.string.ck_png_d)))
        }
        if (r.natives.isNotEmpty() && r.natives.none { it.stored }) {
            add(Check(Status.Info, str(R.string.ck_libs_extracted), str(R.string.ck_libs_extracted_d)))
        }
    }

    /** Below this, converting PNGs saves less than anyone would notice. */
    private const val PNG_WORTH_MENTIONING = 200L * 1024

    // ---- Layer 4: on this phone ------------------------------------------

    private fun deviceApi(r: ApkReport, d: DeviceProfile) = if (d.api >= r.minSdk) {
        Check(Status.Pass, str(R.string.ck_device_api_ok, num(d.api), num(r.minSdk)))
    } else {
        Check(Status.Fail, str(R.string.ck_device_api_low, num(d.api), num(r.minSdk)))
    }

    private fun abi(r: ApkReport, d: DeviceProfile): Check {
        if (r.abis.isEmpty()) return Check(Status.Pass, str(R.string.ck_abi_none_needed))
        val match = d.abis.firstOrNull { it in r.abis }
        return if (match != null) {
            Check(Status.Pass, str(R.string.ck_abi_ok, Msg.Raw(match)))
        } else {
            Check(
                Status.Fail,
                str(R.string.ck_abi_missing, Msg.Raw(d.abis.firstOrNull().orEmpty())),
                str(R.string.ck_abi_missing_d),
                r.abis.map { Msg.Raw(it) },
            )
        }
    }

    /** Libraries this phone would load that cannot be mapped on its pages. */
    private fun pages(r: ApkReport, d: DeviceProfile): Check? {
        if (d.pageSize < Elf.PAGE_16K) return null
        val abi = d.abis.firstOrNull { it in r.abis } ?: return null
        val bad = r.natives.filter { it.abi == abi && (it.elf16k == false || it.zip16k == false) }
        if (bad.isEmpty()) return null
        return Check(Status.Fail, str(R.string.ck_16k_device), str(R.string.ck_16k_device_d), bad.map { Msg.Raw(it.name) })
    }

    private fun space(r: ApkReport, d: DeviceProfile): Check? {
        if (d.freeBytes <= 0) return null
        // Installing briefly holds the APK and what is unpacked from it.
        val needed = r.fileBytes * 2
        return if (d.freeBytes < needed) {
            Check(Status.Warn, str(R.string.ck_space_low, Msg.Raw(needed.readableBytes())))
        } else {
            null
        }
    }

    fun device(r: ApkReport, d: DeviceProfile): List<Check> = buildList {
        add(deviceApi(r, d))
        add(abi(r, d))
        pages(r, d)?.let(::add)
        val missing = r.features.filter { it.required && it.name !in d.features }
        if (missing.isNotEmpty()) {
            add(
                Check(
                    Status.Fail,
                    str(R.string.ck_feature_missing),
                    str(R.string.ck_feature_missing_d),
                    missing.map { Msg.Raw(it.name) },
                ),
            )
        } else if (r.features.any { it.required }) {
            add(Check(Status.Pass, str(R.string.ck_features_ok)))
        }
        if (r.glEsVersion > d.glEs && d.glEs > 0) {
            add(
                Check(
                    Status.Fail,
                    str(
                        R.string.ck_gles_low,
                        Msg.Raw(DeviceProfile.glEsName(r.glEsVersion)),
                        Msg.Raw(DeviceProfile.glEsName(d.glEs)),
                    ),
                ),
            )
        }
        if (r.testOnly) add(testOnly(r))
        signed(r)?.let(::add)
        space(r, d)?.let(::add)
    }

    fun screens(r: ApkReport, d: DeviceProfile): List<Check> = buildList {
        val found = densities(r)
        val bitmaps = found.filter { it in DENSITIES }
        when {
            d.densityBucket in found -> add(Check(Status.Pass, str(R.string.ck_density_ok, Msg.Raw(d.densityBucket))))
            bitmaps.isNotEmpty() -> add(
                Check(
                    Status.Info,
                    str(R.string.ck_density_missing, Msg.Raw(d.densityBucket)),
                    str(R.string.ck_density_missing_d),
                    bitmaps.sortedBy { DENSITIES.indexOf(it) }.map { Msg.Raw(it) },
                ),
            )
            !resourcesShortened(r) -> add(Check(Status.Pass, str(R.string.ck_density_vector)))
        }
        if (!r.screens.large || !r.screens.xlarge) {
            add(Check(Status.Warn, str(R.string.ck_small_screens_only), str(R.string.ck_small_screens_only_d)))
        }
        val locked = r.componentList.filter { it.kind == ComponentKind.Activity && it.orientationLocked }
        add(
            when {
                locked.isEmpty() -> Check(Status.Pass, str(R.string.ck_orient_free))
                r.targetSdk >= 36 -> Check(
                    Status.Info,
                    str(R.string.ck_orient_ignored),
                    str(R.string.ck_orient_ignored_d),
                    locked.map { Msg.Raw(it.name.substringAfterLast('.')) },
                )
                else -> Check(
                    Status.Warn,
                    str(R.string.ck_orient_locked),
                    str(R.string.ck_orient_locked_d),
                    locked.map { Msg.Raw(it.name.substringAfterLast('.')) },
                )
            },
        )
        val fixed = buildList {
            if (r.manifest.resizeable == false) add("application")
            r.manifest.activityResizeable.filterValues { !it }.keys.forEach { add(it.substringAfterLast('.')) }
        }
        if (fixed.isNotEmpty()) {
            add(
                Check(
                    if (r.targetSdk >= 36) Status.Info else Status.Warn,
                    str(R.string.ck_not_resizeable),
                    str(if (r.targetSdk >= 36) R.string.ck_orient_ignored_d else R.string.ck_orient_locked_d),
                    fixed.map { Msg.Raw(it) },
                ),
            )
        }
    }

    /** What happens to one requested permission on this phone. */
    data class PermissionOutcome(val permission: String, val status: Status, val what: Msg)

    fun permissionsOnDevice(r: ApkReport, d: DeviceProfile): List<PermissionOutcome> =
        r.permissions.map { p -> PermissionOutcome(p, outcomeStatus(r, d, p), outcomeText(r, d, p)) }
            .sortedWith(compareByDescending<PermissionOutcome> { it.status.ordinal }.thenBy { it.permission })

    private fun outcomeText(r: ApkReport, d: DeviceProfile, p: String): Msg {
        val max = r.manifest.permissionMaxSdk[p]
        if (max != null && d.api > max) return str(R.string.po_not_requested, num(max))
        when (p) {
            P + "POST_NOTIFICATIONS" -> when {
                d.api < 33 -> return str(R.string.po_notif_old)
                r.targetSdk < 33 -> return str(R.string.po_notif_auto)
            }
            P + "READ_EXTERNAL_STORAGE" ->
                if (d.api >= 33 && r.targetSdk >= 33) return str(R.string.po_no_effect)
            P + "WRITE_EXTERNAL_STORAGE" ->
                if (d.api >= 30 && r.targetSdk >= 30) return str(R.string.po_no_effect)
            P + "ACCESS_BACKGROUND_LOCATION" ->
                if (d.api >= 30 && r.targetSdk >= 30) return str(R.string.po_bg_location)
            P + "SCHEDULE_EXACT_ALARM" ->
                if (d.api >= 34 && r.targetSdk >= 33) return str(R.string.po_exact_alarm)
        }
        return when (r.permissionKinds[p] ?: PermissionKind.Unknown) {
            PermissionKind.Runtime -> str(if (r.targetSdk < 23) R.string.po_legacy else R.string.po_dialog)
            PermissionKind.Special -> str(R.string.po_special)
            PermissionKind.Install -> str(R.string.po_install)
            PermissionKind.Signature -> str(R.string.po_signature)
            PermissionKind.Unknown -> str(R.string.po_unknown)
        }
    }

    private fun outcomeStatus(r: ApkReport, d: DeviceProfile, p: String): Status {
        val max = r.manifest.permissionMaxSdk[p]
        if (max != null && d.api > max) return Status.Pass
        if (p == P + "READ_EXTERNAL_STORAGE" && d.api >= 33 && r.targetSdk >= 33) return Status.Warn
        if (p == P + "WRITE_EXTERNAL_STORAGE" && d.api >= 30 && r.targetSdk >= 30) return Status.Warn
        if (p == P + "POST_NOTIFICATIONS" && d.api < 33) return Status.Pass
        return when (r.permissionKinds[p] ?: PermissionKind.Unknown) {
            PermissionKind.Runtime -> if (r.targetSdk < 23) Status.Warn else Status.Info
            PermissionKind.Special -> Status.Info
            PermissionKind.Install -> Status.Pass
            PermissionKind.Signature -> Status.Warn
            PermissionKind.Unknown -> Status.Info
        }
    }

    fun install(r: ApkReport, d: DeviceProfile): List<Check> = buildList {
        add(deviceApi(r, d))
        add(abi(r, d))
        pages(r, d)?.let(::add)
        add(testOnly(r))
        signed(r)?.let(::add)
        space(r, d)?.let(::add)
    }

    /** Whether [next] can replace [installed] on a phone that has it. */
    fun update(installed: ApkReport, next: ApkReport): List<Check> = buildList {
        if (installed.packageName != next.packageName) {
            add(
                Check(
                    Status.Fail,
                    str(R.string.ck_update_package),
                    str(R.string.ck_update_package_d),
                    listOf(Msg.Raw(installed.packageName), Msg.Raw(next.packageName)),
                ),
            )
            return@buildList
        }
        add(
            when {
                next.versionCode > installed.versionCode -> Check(
                    Status.Pass,
                    str(R.string.ck_update_version_ok, num(installed.versionCode), num(next.versionCode)),
                )
                next.versionCode == installed.versionCode -> Check(
                    Status.Warn,
                    str(R.string.ck_update_version_same),
                    str(R.string.ck_update_version_same_d),
                )
                else -> Check(
                    Status.Fail,
                    str(R.string.ck_update_version_down, num(installed.versionCode), num(next.versionCode)),
                    str(R.string.ck_update_version_down_d),
                )
            },
        )
        val before = installed.signatureSha256
        val after = next.signatureSha256
        if (before != null && after != null) {
            add(
                if (before == after) {
                    Check(Status.Pass, str(R.string.ck_update_cert_same))
                } else {
                    Check(Status.Fail, str(R.string.ck_update_cert_diff), str(R.string.ck_update_cert_diff_d))
                },
            )
        }
        if (next.minSdk > installed.minSdk) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.ck_update_min_raised, num(installed.minSdk), num(next.minSdk)),
                    str(R.string.ck_update_min_raised_d),
                ),
            )
        }
        if (next.targetSdk != installed.targetSdk) {
            add(
                Check(
                    Status.Info,
                    str(R.string.ck_update_target, num(installed.targetSdk), num(next.targetSdk)),
                    str(R.string.ck_update_target_d),
                ),
            )
        }
        val added = next.dangerousPermissions - installed.dangerousPermissions.toSet()
        if (added.isNotEmpty()) {
            add(
                Check(
                    Status.Info,
                    str(R.string.ck_update_new_perms),
                    str(R.string.ck_update_new_perms_d),
                    added.map { Msg.Raw(it.substringAfterLast('.')) },
                ),
            )
        }
        val dropped = installed.abis - next.abis.toSet()
        if (dropped.isNotEmpty()) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.ck_update_abi_dropped),
                    str(R.string.ck_update_abi_dropped_d),
                    dropped.map { Msg.Raw(it) },
                ),
            )
        }
    }

    // ---- Layer 5: can it ship --------------------------------------------

    /** Everything Play or the first install could refuse, pass or fail. */
    fun release(r: ApkReport): List<Check> = buildList {
        signed(r)?.let(::add)
        certificate(r)?.let(::add)
        scheme(r)?.let(::add)
        add(debuggable(r))
        add(testOnly(r))
        add(target(r))
        add(r8(r))
        dexMode(r)?.let(::add)
        sixtyFourBit(r)?.let(::add)
        addAll(sixteenK(r))
        add(cleartext(r))
        add(versionCode(r))
        exported(r)?.let(::add)
        if (unruledBackup(r)) add(Check(Status.Warn, str(R.string.f_backup_what), str(R.string.f_backup_why)))
    }

    fun version(r: ApkReport): List<Check> = buildList {
        add(versionCode(r))
        when {
            r.versionName.isBlank() ->
                add(Check(Status.Warn, str(R.string.ck_version_name_missing), str(R.string.ck_version_name_missing_d)))
            PRE_RELEASE.containsMatchIn(r.versionName) ->
                add(Check(Status.Info, str(R.string.ck_version_prerelease), evidence = listOf(Msg.Raw(r.versionName))))
        }
        add(debuggable(r))
        dexMode(r)?.let(::add)
    }

    fun signingVerification(r: ApkReport): List<Check> = signing(r)

    fun targetSdk(r: ApkReport): List<Check> = sdk(r, null)

    /** The four places a debug build gives itself away. */
    fun debugSignals(r: ApkReport): List<Check> = listOfNotNull(
        debuggable(r),
        certificate(r),
        dexMode(r),
        testOnly(r),
    )

    fun releaseConfig(r: ApkReport): List<Check> = buildList {
        add(r8(r))
        dexMode(r)?.let(::add)
        add(debuggable(r))
        add(cleartext(r))
        if (r.items.any { it.name.startsWith("res/") }) {
            add(
                if (resourcesShortened(r)) {
                    Check(Status.Pass, str(R.string.ck_res_opt))
                } else {
                    Check(Status.Info, str(R.string.ck_res_not_opt), str(R.string.ck_res_not_opt_d))
                },
            )
        }
        if (r.largeHeap) add(Check(Status.Info, str(R.string.ck_large_heap), str(R.string.ck_large_heap_d)))
    }

    fun proguard(r: ApkReport): List<Check> = buildList {
        add(r8(r))
        val marker = r.compiler
        if (marker?.shrunk == true) {
            when (marker.r8Mode) {
                "full" -> add(Check(Status.Pass, str(R.string.ck_r8_full)))
                "compatibility" -> add(Check(Status.Info, str(R.string.ck_r8_compat), str(R.string.ck_r8_compat_d)))
            }
            r.code.obfuscatedPercent?.let { p ->
                add(
                    if (p >= 50) {
                        Check(Status.Pass, str(R.string.ck_obf_high, Msg.Raw("$p%")))
                    } else {
                        Check(Status.Info, str(R.string.ck_obf_low, Msg.Raw("$p%")), str(R.string.ck_obf_low_d))
                    },
                )
            }
            add(Check(Status.Info, str(R.string.ck_mapping), str(R.string.ck_mapping_d)))
        }
    }

    fun backup(r: ApkReport): List<Check> = buildList {
        if (!r.manifest.decoded) add(Check(Status.Info, str(R.string.ck_manifest_unread)))
        if (!r.allowsBackup) {
            add(Check(Status.Pass, str(R.string.ck_backup_off)))
            if (r.targetSdk >= 31) {
                add(Check(Status.Info, str(R.string.ck_backup_off_transfer), str(R.string.ck_backup_off_transfer_d)))
            }
            return@buildList
        }
        when {
            r.manifest.dataExtractionRules -> add(Check(Status.Pass, str(R.string.ck_backup_extraction_rules)))
            r.manifest.fullBackupContent == BackupRules.Rules && r.targetSdk >= 31 ->
                add(Check(Status.Info, str(R.string.ck_backup_full_only), str(R.string.ck_backup_full_only_d)))
            r.manifest.fullBackupContent == BackupRules.Rules ->
                add(Check(Status.Pass, str(R.string.ck_backup_full_rules)))
            r.manifest.fullBackupContent == BackupRules.Disabled ->
                add(Check(Status.Info, str(R.string.ck_backup_full_disabled)))
            else -> add(Check(Status.Warn, str(R.string.f_backup_what), str(R.string.f_backup_why)))
        }
    }

    fun privacy(r: ApkReport): List<Check> = buildList {
        add(cleartext(r))
        if (r.manifest.networkSecurityConfig) add(Check(Status.Pass, str(R.string.ck_nsc)))
        val openProviders = openComponents(r).filter { it.kind == ComponentKind.Provider }
        if (openProviders.isNotEmpty()) {
            add(
                Check(
                    Status.Warn,
                    str(R.string.ck_provider_open),
                    str(R.string.ck_provider_open_d),
                    openProviders.map { Msg.Raw(it.authority ?: it.name.substringAfterLast('.')) },
                ),
            )
        }
        if (unruledBackup(r)) add(Check(Status.Warn, str(R.string.f_backup_what), str(R.string.f_backup_why)))
        if (r.requests("com.google.android.gms.permission.AD_ID")) {
            add(Check(Status.Info, str(R.string.ck_ad_id), str(R.string.pol_ad_id)))
        }
        if (r.dangerousPermissions.isNotEmpty()) {
            add(
                Check(
                    Status.Info,
                    plural(R.plurals.f_perms_what, r.dangerousPermissions.size, r.dangerousPermissions.size),
                    str(R.string.f_perms_why),
                    r.dangerousPermissions.map { Msg.Raw(it.substringAfterLast('.')) },
                ),
            )
        }
    }

    /** What would break on a device after a clean install, not at upload. */
    fun prelaunch(r: ApkReport): List<Check> = buildList {
        val typed = r.manifest.serviceTypes.filterValues { it != 0 }
        if (typed.isNotEmpty()) {
            if (r.targetSdk >= 28 && !r.requests(P + "FOREGROUND_SERVICE")) {
                add(Check(Status.Fail, str(R.string.ck_fgs_base_missing), str(R.string.ck_fgs_base_missing_d)))
            }
            if (r.targetSdk >= 34) {
                val missing = typed.values.flatMap { ForegroundTypes.required(it) }.distinct()
                    .filterNot { r.requests(it) }
                add(
                    if (missing.isEmpty()) {
                        Check(Status.Pass, str(R.string.ck_fgs_ok))
                    } else {
                        Check(
                            Status.Fail,
                            str(R.string.ck_fgs_missing),
                            str(R.string.ck_fgs_missing_d),
                            missing.map { Msg.Raw(it.removePrefix(P)) },
                        )
                    },
                )
            }
        }
        if (r.requests(P + "SCHEDULE_EXACT_ALARM") && r.targetSdk >= 33) {
            add(Check(Status.Warn, str(R.string.ck_exact_alarm), str(R.string.ck_exact_alarm_d)))
        }
        if (r.targetSdk >= 33 && !r.requests(P + "POST_NOTIFICATIONS")) {
            add(Check(Status.Info, str(R.string.ck_no_notifications), str(R.string.ck_no_notifications_d)))
        }
        sixtyFourBit(r)?.takeIf { it.status != Status.Pass }?.let(::add)
        addAll(sixteenK(r).filter { it.status != Status.Pass })
        if (r.manifest.requestLegacyExternalStorage && r.targetSdk >= 30) {
            add(Check(Status.Info, str(R.string.ck_legacy_storage), str(R.string.ck_legacy_storage_d)))
        }
        val locked = r.componentList.filter { it.kind == ComponentKind.Activity && it.orientationLocked }
        if (locked.isNotEmpty() && r.targetSdk >= 36) {
            add(
                Check(
                    Status.Info,
                    str(R.string.ck_orient_ignored),
                    str(R.string.ck_orient_ignored_d),
                    locked.map { Msg.Raw(it.name.substringAfterLast('.')) },
                ),
            )
        }
        if (r.targetSdk >= 35) add(Check(Status.Info, str(R.string.ck_edge_to_edge)))
        if (isEmpty()) add(Check(Status.Pass, str(R.string.ck_prelaunch_clear)))
    }
}
