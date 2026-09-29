package com.devbangs.onedevs.ui.lab

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.ComponentKind
import com.devbangs.onedevs.lab.DeviceProfile
import com.devbangs.onedevs.lab.Findings
import com.devbangs.onedevs.lab.ForegroundTypes
import com.devbangs.onedevs.lab.KnownSdks
import com.devbangs.onedevs.lab.PermissionKind
import com.devbangs.onedevs.lab.Platform
import com.devbangs.onedevs.lab.PlayPolicy
import com.devbangs.onedevs.lab.BackupRules
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.sliceSizes
import com.devbangs.onedevs.lab.str

// Layer two: what was built. Every tool here is a different question asked of
// the same parse, which is why they share one analysis and none of them opens
// the file again.

/** "API 34 · Android 14", or just the API level for one this table predates. */
internal fun apiLabel(api: Int): String =
    Platform.version(api)?.let { "API $api · Android $it" } ?: "API $api"

/** A class name as the manifest would abbreviate it, when it can. */
internal fun ApkReport.shortName(name: String): String =
    if (name.startsWith("$packageName.")) name.removePrefix(packageName) else name

@Composable
internal fun ManifestTool(r: ApkReport) {
    Facts(
        stringResource(R.string.lt_manifest_app),
        listOf(
            "package" to r.packageName,
            "versionCode" to r.versionCode.toString(),
            "versionName" to r.versionName,
            "minSdkVersion" to r.minSdk.toString(),
            "targetSdkVersion" to r.targetSdk.toString(),
            "compileSdkVersion" to r.compileSdk.takeIf { it > 0 }?.toString().orEmpty(),
            "application" to r.appClass.orEmpty(),
        ),
    )
    Facts(
        stringResource(R.string.lt_manifest_flags),
        buildList {
            add("debuggable" to r.debuggable.toString())
            add("allowBackup" to r.allowsBackup.toString())
            add("usesCleartextTraffic" to r.allowsCleartext.toString())
            add("testOnly" to r.testOnly.toString())
            add("largeHeap" to r.largeHeap.toString())
            add("extractNativeLibs" to r.extractNativeLibs.toString())
            if (r.manifest.decoded) {
                add("networkSecurityConfig" to yesNo(r.manifest.networkSecurityConfig))
                add("dataExtractionRules" to yesNo(r.manifest.dataExtractionRules))
                add("fullBackupContent" to backupRules(r.manifest.fullBackupContent))
                add("localeConfig" to yesNo(r.manifest.localeConfig))
            }
        },
    )
    val xml = r.manifestXml
    if (xml != null) {
        CodeBlock("AndroidManifest.xml", xml)
    } else {
        Empty(stringResource(R.string.lt_manifest_unreadable))
    }
}

@Composable
internal fun backupRules(rules: BackupRules): String = when (rules) {
    BackupRules.Absent -> yesNo(false)
    BackupRules.Rules -> yesNo(true)
    BackupRules.Disabled -> "false"
}

@Composable
internal fun kindLabel(kind: PermissionKind): String = stringResource(
    when (kind) {
        PermissionKind.Runtime -> R.string.pk_runtime
        PermissionKind.Special -> R.string.pk_special
        PermissionKind.Install -> R.string.pk_install
        PermissionKind.Signature -> R.string.pk_signature
        PermissionKind.Unknown -> R.string.pk_unknown
    },
)

@Composable
internal fun PermissionsTool(r: ApkReport) {
    if (r.permissions.isEmpty()) {
        Empty(stringResource(R.string.lt_perm_none))
        return
    }
    val grouped = r.permissions.groupBy { r.permissionKinds[it] ?: PermissionKind.Unknown }
    val policy = buildList {
        r.permissions.forEach { p ->
            PlayPolicy.declarationFor(p, r.targetSdk)?.let {
                add(Check(Status.Warn, Msg.Raw(p.substringAfterLast('.')), str(it)))
            }
        }
        r.componentList.filter { it.kind == ComponentKind.Service }.forEach { s ->
            PlayPolicy.servicePermissions[s.permission]?.let {
                add(Check(Status.Warn, Msg.Raw(r.shortName(s.name)), str(it)))
            }
        }
    }
    if (policy.isNotEmpty()) {
        SectionTitle(stringResource(R.string.lt_perm_policy))
        CheckList(policy)
    }
    PermissionKind.entries.forEach { kind ->
        val names = grouped[kind].orEmpty()
        if (names.isNotEmpty()) {
            ListCard(
                "${kindLabel(kind)} · ${names.size}",
                names.map { p ->
                    val short = if (p.startsWith("android.permission.")) p.substringAfterLast('.') else p
                    short to (r.manifest.permissionMaxSdk[p]?.let { "maxSdkVersion $it" }.orEmpty())
                },
            )
        }
    }
}

@Composable
internal fun SdkTool(r: ApkReport, d: DeviceProfile) {
    Facts(
        stringResource(R.string.apk_sdk),
        listOf(
            "minSdk" to apiLabel(r.minSdk),
            "targetSdk" to apiLabel(r.targetSdk),
            "compileSdk" to r.compileSdk.takeIf { it > 0 }?.let(::apiLabel).orEmpty(),
            stringResource(R.string.lt_play_floor) to apiLabel(ApkReport.PLAY_TARGET_SDK_FLOOR),
            stringResource(R.string.lt_newest) to apiLabel(Platform.LATEST),
            stringResource(R.string.lt_this_phone) to "API ${d.api} · Android ${d.release}",
        ),
    )
    CheckList(Checks.sdk(r, d))
}

@Composable
internal fun ComponentsTool(r: ApkReport) {
    CheckList(Checks.components(r))
    val launcher = stringResource(R.string.lt_launcher)
    val titles = mapOf(
        ComponentKind.Activity to R.string.comp_activities,
        ComponentKind.Service to R.string.comp_services,
        ComponentKind.Receiver to R.string.comp_receivers,
        ComponentKind.Provider to R.string.comp_providers,
    )
    if (r.componentList.isEmpty()) {
        Empty(stringResource(R.string.lt_comp_none))
        return
    }
    titles.forEach { (kind, title) ->
        val list = r.componentList.filter { it.kind == kind }
        if (list.isNotEmpty()) {
            ListCard(
                "${stringResource(title)} · ${list.size}",
                list.map { c ->
                    val tags = buildList {
                        if (c.name in r.manifest.launchers) add(launcher)
                        if (c.exported) add("exported")
                        c.permission?.let { add(it.substringAfterLast('.')) }
                        c.authority?.let { add(it) }
                        r.manifest.serviceTypes[c.name]?.let { addAll(ForegroundTypes.names(it)) }
                    }
                    r.shortName(c.name) to tags.joinToString(" · ")
                },
            )
        }
    }
}

@Composable
internal fun ResourcesTool(r: ApkReport) {
    val res = r.items.filter { it.name.startsWith("res/") }
    val assets = r.items.filter { it.name.startsWith("assets/") }
    val table = r.items.firstOrNull { it.name == "resources.arsc" }
    Facts(
        stringResource(R.string.apk_contents),
        listOf(
            "resources.arsc" to (table?.size?.readable() ?: ""),
            "res/" to if (res.isEmpty()) "" else "${res.size.grouped()} · ${res.sumOf { it.size }.readable()}",
            "assets/" to if (assets.isEmpty()) "" else "${assets.size.grouped()} · ${assets.sumOf { it.size }.readable()}",
        ),
    )
    if (Checks.resourcesShortened(r)) {
        CheckList(listOf(Check(Status.Pass, str(R.string.ck_res_opt))))
    } else {
        val types = res.mapNotNull { it.name.split('/').getOrNull(1)?.substringBefore('-') }
            .filter { it.isNotEmpty() && !it.contains('.') }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        Facts(stringResource(R.string.lt_res_types), types.map { it.key to it.value.grouped() })
        val densities = Checks.densities(r)
        if (densities.isNotEmpty()) {
            Facts(
                stringResource(R.string.lt_res_densities),
                densities.map { q ->
                    q to res.count { e -> e.name.split('/').getOrNull(1)?.split('-')?.contains(q) == true }.grouped()
                },
            )
        }
    }
    Facts(
        stringResource(R.string.lt_largest),
        (res + assets).sortedByDescending { it.compressedSize }.take(LARGEST)
            .map { it.name to it.compressedSize.readable() },
    )
}

private const val LARGEST = 12

@Composable
internal fun NativesTool(r: ApkReport) {
    if (r.natives.isEmpty()) {
        Empty(stringResource(R.string.lt_native_none))
        return
    }
    CheckList(Checks.natives(r))
    val stored = stringResource(R.string.lt_stored)
    val compressed = stringResource(R.string.lt_compressed)
    r.natives.groupBy { it.abi }.forEach { (abi, libs) ->
        ListCard(
            "$abi · ${libs.sumOf { it.bytes }.readable()}",
            libs.map { lib ->
                val parts = buildList {
                    add(lib.bytes.readable())
                    add(if (lib.stored) stored else compressed)
                    val aligned = lib.elf16k?.let { elf -> elf && lib.zip16k != false }
                    if (aligned != null) add(if (aligned) "16 KB ✓" else "16 KB ✕")
                }
                lib.name to parts.joinToString(" · ")
            },
        )
    }
}

@Composable
internal fun CertificatesTool(r: ApkReport) {
    CheckList(Checks.signing(r))
    Facts(
        stringResource(R.string.lt_sign_schemes),
        listOf(
            "v1 (JAR)" to yesNo(r.schemes.v1),
            "v2" to yesNo(r.schemes.v2),
            "v3" to yesNo(r.schemes.v3),
            "v3.1" to yesNo(r.schemes.v31),
        ),
    )
    r.certificates.forEach { c ->
        Facts(
            stringResource(R.string.lt_sign_cert),
            listOf(
                stringResource(R.string.lt_subject) to c.subject,
                stringResource(R.string.lt_issuer) to c.issuer,
                stringResource(R.string.lt_valid_from) to Msg.Date(c.notBefore).resolve(),
                stringResource(R.string.lt_valid_until) to Msg.Date(c.notAfter).resolve(),
                stringResource(R.string.lt_algorithm) to c.algorithm,
                stringResource(R.string.lt_serial) to c.serial,
            ),
        )
        Copyable("SHA-256", c.sha256)
        Copyable("SHA-1", c.sha1)
    }
    Note(stringResource(R.string.apk_signing_compare))
}

/** R8 or D8, its version and mode, as a facts row. */
@Composable
internal fun compilerRows(r: ApkReport): List<Pair<String, String>> {
    val m = r.compiler ?: return listOf(stringResource(R.string.lt_compiler) to stringResource(R.string.lt_unknown))
    return listOf(
        stringResource(R.string.lt_compiler) to listOfNotNull(m.tool, m.version).joinToString(" "),
        "compilation-mode" to m.mode.orEmpty(),
        "min-api" to m.minApi?.toString().orEmpty(),
        "r8-mode" to m.r8Mode.orEmpty(),
    )
}

@Composable
internal fun BuildConfigTool(r: ApkReport) {
    Facts(
        stringResource(R.string.lt_build),
        buildList {
            add(
                stringResource(R.string.lt_build_type) to
                    stringResource(if (r.debuggable) R.string.lt_debug else R.string.lt_release),
            )
            addAll(compilerRows(r))
            add(stringResource(R.string.lt_agp) to r.buildMetadata["androidGradlePluginVersion"].orEmpty())
            r.code.obfuscatedPercent?.let { add(stringResource(R.string.lt_obfuscated) to "$it%") }
            add("debuggable" to r.debuggable.toString())
            add("testOnly" to r.testOnly.toString())
            add("largeHeap" to r.largeHeap.toString())
            add("extractNativeLibs" to r.extractNativeLibs.toString())
        },
    )
}

@Composable
internal fun SizeTool(r: ApkReport) {
    val unpacked = sliceSizes(r.items.map { it.name to it.size }).associateBy { it.label }
    val packed = sliceSizes(r.items.map { it.name to it.compressedSize })
    Facts(
        stringResource(R.string.apk_contents),
        listOf(
            stringResource(R.string.apk_size) to r.fileBytes.readable(),
            stringResource(R.string.lt_unpacked) to r.items.sumOf { it.size }.readable(),
        ),
    )
    Facts(
        stringResource(R.string.lt_size_slices),
        packed.map { slice ->
            slice.label to "${slice.bytes.readable()} · ${(unpacked[slice.label]?.bytes ?: 0L).readable()}"
        },
    )
    Facts(
        stringResource(R.string.lt_largest),
        r.items.sortedByDescending { it.compressedSize }.take(LARGEST)
            .map { it.name to it.compressedSize.readable() },
    )
    CheckList(Checks.size(r))
}

@Composable
internal fun DexTool(r: ApkReport) {
    Findings.packed(r)?.let { FindingCard(it) }
    val largest = r.dexFiles.maxOfOrNull { it.counts.methods } ?: 0
    Facts(
        stringResource(R.string.lt_totals),
        buildList {
            add(stringResource(R.string.apk_dex) to r.dexEntries.grouped())
            if (Findings.methodCountIsMeaningful(r)) {
                add(stringResource(R.string.apk_methods) to r.dex.methods.grouped())
                add(stringResource(R.string.lt_dex_largest) to "${largest * 100 / 65_536}%")
            }
            add(stringResource(R.string.lt_fields) to r.dex.fields.grouped())
            add(stringResource(R.string.lt_classes) to r.dex.classes.grouped())
            add(stringResource(R.string.lt_strings) to r.dex.strings.grouped())
        },
    )
    if (r.dexFiles.isNotEmpty()) {
        Facts(
            stringResource(R.string.lt_dex_files),
            r.dexFiles.map { it.name to "${it.counts.methods.grouped()} · ${it.bytes.readable()}" },
        )
    }
    Facts(stringResource(R.string.lt_compiler), compilerRows(r))
    if (r.code.packages.isNotEmpty()) {
        Facts(stringResource(R.string.lt_dex_packages), r.code.packages.map { it.first to it.second.grouped() })
    }
    if (Findings.multidexWorthMentioning(r)) Note(stringResource(R.string.lt_multidex))
}

@Composable
internal fun PrivacySdks(r: ApkReport) {
    SectionTitle(stringResource(R.string.lt_sdks))
    val found = r.code.sdks.mapNotNull { KnownSdks.byId(it) }
    if (found.isEmpty()) {
        Empty(stringResource(R.string.lt_sdks_none))
        return
    }
    ListCard(
        stringResource(R.string.lt_sdks_found),
        found.sortedWith(compareBy({ it.kind.ordinal }, { it.name })).map { it.name to stringResource(it.kind.label) },
    )
    Note(stringResource(R.string.lt_sdks_d))
}
