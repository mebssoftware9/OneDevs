package com.devbangs.onedevs.ui.lab

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.DeviceProfile
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.Platform
import com.devbangs.onedevs.lab.verdict
import com.devbangs.onedevs.ui.theme.oneDevsColors

// Layer four: does it run everywhere. "Everywhere" is out of reach from one
// phone, so these answer the two questions that are not: will it run on this
// one, and what changes across the versions it claims to support.

@Composable
private fun ThisPhone(d: DeviceProfile) {
    Facts(
        stringResource(R.string.lt_this_phone),
        listOf(
            stringResource(R.string.lt_model) to d.model,
            "Android" to "${d.release} · API ${d.api}",
            "ABI" to d.abis.joinToString(", "),
            stringResource(R.string.lt_page_size) to "${d.pageSize / 1024} KB",
            "OpenGL ES" to if (d.glEs > 0) DeviceProfile.glEsName(d.glEs) else "",
            stringResource(R.string.lt_screen) to "${d.widthDp} × ${d.heightDp} dp",
            stringResource(R.string.lt_density) to "${d.densityDpi} dpi · ${d.densityBucket}",
        ),
    )
}

@Composable
internal fun DeviceCompatTool(r: ApkReport, d: DeviceProfile) {
    val checks = Checks.device(r, d)
    VerdictBanner(checks.verdict(), R.string.lt_device_ready, R.string.lt_device_warn, R.string.lt_device_blocked)
    CheckList(checks)
    ThisPhone(d)
}

/**
 * Every Android version the APK installs on, oldest first, each with what
 * targeting it switched on. Versions above the target run the app in
 * compatibility mode, which is its own thing to test.
 */
@Composable
internal fun VersionsTool(r: ApkReport, d: DeviceProfile) {
    Note(stringResource(R.string.lt_versions_intro))
    val oldest = maxOf(r.minSdk, 21)
    for (api in oldest..Platform.LATEST) {
        val tags = buildList {
            if (api == r.minSdk) add(stringResource(R.string.lt_ver_min))
            if (api == r.targetSdk) add(stringResource(R.string.lt_ver_target))
            if (api == d.api) add(stringResource(R.string.lt_this_phone))
            if (api > r.targetSdk) add(stringResource(R.string.lt_ver_compat))
        }
        val changes = if (api <= r.targetSdk) Platform.changes[api].orEmpty() else emptyList()
        VersionRow(apiLabel(api), tags, changes.map { stringResource(it) }, current = api == d.api)
    }
}

@Composable
private fun VersionRow(title: String, tags: List<String>, lines: List<String>, current: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val accent = oneDevsColors.caution
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (current) accent.solid else scheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Medium,
                    color = accent.solid,
                )
            }
        }
        lines.forEach {
            Text(
                text = "• $it",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun ScreensTool(r: ApkReport, d: DeviceProfile) {
    CheckList(Checks.screens(r, d))
    Facts(
        stringResource(R.string.lt_this_phone),
        listOf(
            stringResource(R.string.lt_screen) to "${d.widthDp} × ${d.heightDp} dp",
            stringResource(R.string.lt_smallest_width) to "${d.smallestWidthDp} dp",
            stringResource(R.string.lt_density) to "${d.densityDpi} dpi · ${d.densityBucket}",
        ),
    )
    Facts(
        "supports-screens",
        listOf(
            "smallScreens" to r.screens.small.toString(),
            "normalScreens" to r.screens.normal.toString(),
            "largeScreens" to r.screens.large.toString(),
            "xlargeScreens" to r.screens.xlarge.toString(),
            "anyDensity" to r.screens.anyDensity.toString(),
        ),
    )
}

@Composable
internal fun PermissionTestingTool(r: ApkReport, d: DeviceProfile) {
    if (r.permissions.isEmpty()) {
        Empty(stringResource(R.string.lt_perm_none))
        return
    }
    Note(stringResource(R.string.lt_perm_test_intro, d.release, d.api.toString()))
    CheckList(
        Checks.permissionsOnDevice(r, d).map { o ->
            val short = if (o.permission.startsWith("android.permission.")) {
                o.permission.substringAfterLast('.')
            } else {
                o.permission
            }
            Check(o.status, Msg.Raw(short), o.what)
        },
        sorted = false,
    )
}

@Composable
internal fun InstallTool(
    r: ApkReport,
    d: DeviceProfile,
    installed: ApkReport?,
    failure: String?,
    onPickInstalled: () -> Unit,
) {
    SectionTitle(stringResource(R.string.lt_install_here))
    val here = Checks.install(r, d)
    VerdictBanner(here.verdict(), R.string.lt_device_ready, R.string.lt_device_warn, R.string.lt_device_blocked)
    CheckList(here)

    SectionTitle(stringResource(R.string.lt_update_title))
    Note(stringResource(R.string.lt_update_intro))
    Pill(stringResource(if (installed == null) R.string.lt_update_pick else R.string.lt_update_pick_other), onPickInstalled)
    failure?.let {
        Text(
            text = stringResource(R.string.lab_failed, it),
            style = MaterialTheme.typography.bodySmall,
            color = oneDevsColors.critical.solid,
        )
    }
    if (installed != null) {
        Facts(
            stringResource(R.string.lt_update_from),
            listOf(
                installed.fileName to "${installed.versionName} (${installed.versionCode})",
                r.fileName to "${r.versionName} (${r.versionCode})",
            ),
        )
        val update = Checks.update(installed, r)
        VerdictBanner(update.verdict(), R.string.lt_update_ready, R.string.lt_update_warn, R.string.lt_update_blocked)
        CheckList(update)
    }
}
