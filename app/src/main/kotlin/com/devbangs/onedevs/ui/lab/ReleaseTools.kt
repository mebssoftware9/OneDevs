package com.devbangs.onedevs.ui.lab

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.Platform
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.Verdict
import com.devbangs.onedevs.lab.verdict

// Layer five: is it safe to ship. Most of these are the same few questions
// seen from a different side -- readiness is the verdict, the checklist is the
// working, the debug detector is four of the lines -- so they share Checks and
// differ in what they put first.

@Composable
internal fun ReadinessTool(r: ApkReport) {
    val checks = Checks.release(r)
    VerdictBanner(checks.verdict(), R.string.lt_ready_yes, R.string.lt_ready_warn, R.string.lt_ready_no)
    Facts(
        stringResource(R.string.lt_ready_counts),
        listOf(
            stringResource(R.string.st_fail) to checks.count { it.status == Status.Fail }.grouped(),
            stringResource(R.string.st_warn) to checks.count { it.status == Status.Warn }.grouped(),
            stringResource(R.string.st_pass) to checks.count { it.status == Status.Pass }.grouped(),
        ),
    )
    val open = checks.filter { it.status == Status.Fail || it.status == Status.Warn }
    if (open.isEmpty()) Empty(stringResource(R.string.apk_clear)) else CheckList(open)
}

@Composable
internal fun VersionTool(r: ApkReport) {
    Facts(
        stringResource(R.string.lt_build),
        buildList {
            add("versionName" to r.versionName)
            add("versionCode" to r.versionCode.toString())
            add("minSdk" to apiLabel(r.minSdk))
            add("targetSdk" to apiLabel(r.targetSdk))
            if (r.compileSdk > 0) add("compileSdk" to apiLabel(r.compileSdk))
            add(stringResource(R.string.lt_agp) to r.buildMetadata["androidGradlePluginVersion"].orEmpty())
            addAll(compilerRows(r).take(1))
        },
    )
    CheckList(Checks.version(r))
}

@Composable
internal fun SigningTool(r: ApkReport) {
    val checks = Checks.signingVerification(r)
    VerdictBanner(checks.verdict(), R.string.lt_sign_ready, R.string.lt_sign_warn, R.string.lt_sign_blocked)
    CheckList(checks)
    r.signatureSha256?.let { Copyable("SHA-256", it) }
    Note(stringResource(R.string.apk_signing_compare))
}

@Composable
internal fun TargetTool(r: ApkReport) {
    Facts(
        stringResource(R.string.apk_sdk),
        listOf(
            "targetSdk" to apiLabel(r.targetSdk),
            stringResource(R.string.lt_play_floor) to apiLabel(ApkReport.PLAY_TARGET_SDK_FLOOR),
            stringResource(R.string.lt_newest) to apiLabel(Platform.LATEST),
        ),
    )
    CheckList(Checks.targetSdk(r))
    SectionTitle(stringResource(R.string.lt_target_changes))
    val ahead = Platform.changesBetween(r.targetSdk, Platform.LATEST)
    if (ahead.isEmpty()) {
        Empty(stringResource(R.string.lt_target_nothing))
    } else {
        ahead.forEach { (api, changes) ->
            ListCard(apiLabel(api), changes.map { stringResource(it) to "" })
        }
    }
}

@Composable
internal fun ChecklistTool(r: ApkReport) {
    Note(stringResource(R.string.lt_checklist_intro))
    CheckList(Checks.release(r), sorted = false)
}

@Composable
internal fun DebugTool(r: ApkReport) {
    val checks = Checks.debugSignals(r)
    // Pass or fail only: every signal is either there or not, so there is no
    // middle verdict to show.
    VerdictBanner(
        if (checks.verdict() == Verdict.Blocked) Verdict.Blocked else Verdict.Ready,
        R.string.lt_debug_no,
        R.string.lt_debug_no,
        R.string.lt_debug_yes,
    )
    CheckList(checks)
}

@Composable
internal fun ReleaseConfigTool(r: ApkReport) {
    CheckList(Checks.releaseConfig(r))
}

@Composable
internal fun ProguardTool(r: ApkReport) {
    Facts(
        stringResource(R.string.lt_compiler),
        buildList {
            addAll(compilerRows(r))
            r.code.obfuscatedPercent?.let { add(stringResource(R.string.lt_obfuscated) to "$it%") }
        },
    )
    CheckList(Checks.proguard(r))
}

@Composable
internal fun BackupTool(r: ApkReport) {
    Facts(
        stringResource(R.string.lt_manifest_flags),
        buildList {
            add("allowBackup" to r.allowsBackup.toString())
            if (r.manifest.decoded) {
                add("fullBackupContent" to backupRules(r.manifest.fullBackupContent))
                add("dataExtractionRules" to yesNo(r.manifest.dataExtractionRules))
            }
        },
    )
    CheckList(Checks.backup(r))
}

@Composable
internal fun PrivacyTool(r: ApkReport) {
    CheckList(Checks.privacy(r))
    PrivacySdks(r)
}

@Composable
internal fun PrelaunchTool(r: ApkReport) {
    val checks = Checks.prelaunch(r)
    VerdictBanner(checks.verdict(), R.string.lt_prelaunch_ready, R.string.lt_prelaunch_warn, R.string.lt_prelaunch_blocked)
    CheckList(checks)
}
