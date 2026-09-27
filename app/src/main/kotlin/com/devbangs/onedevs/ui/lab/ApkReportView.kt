package com.devbangs.onedevs.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.util.Locale

/**
 * What one APK turned out to be.
 *
 * Findings first, facts after. A developer opening this the night before a
 * release wants to know whether anything will stop them, and four lines of
 * that is worth more than forty lines of correct detail they have to read
 * through to find it.
 */
@Composable
fun ApkReportView(
    report: ApkReport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = report.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = "${report.packageName} · ${report.versionName} (${report.versionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.lab_close),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // The things that stop a release, if any of them are true.
        val findings = buildList {
            if (report.debuggable) add(stringResource(R.string.apk_finding_debuggable) to true)
            if (!report.meetsPlayTargetFloor) {
                add(
                    stringResource(
                        R.string.apk_finding_target, report.targetSdk, ApkReport.PLAY_TARGET_SDK_FLOOR,
                    ) to true,
                )
            }
            if (report.signatureSha256 == null) add(stringResource(R.string.apk_finding_unsigned) to true)
            if (report.allowsCleartext) add(stringResource(R.string.apk_finding_cleartext) to false)
            if (report.dex.overSingleDexLimit) {
                add(
                    pluralStringResource(
                        R.plurals.apk_finding_multidex, report.dex.methods, report.dex.methods,
                    ) to false,
                )
            }
        }
        if (findings.isEmpty()) {
            Finding(stringResource(R.string.apk_finding_none), blocking = false, good = true)
        } else {
            findings.forEach { (text, blocking) -> Finding(text, blocking) }
        }

        Facts(
            stringResource(R.string.apk_sdk),
            listOf(
                "minSdk" to "${report.minSdk}",
                "targetSdk" to "${report.targetSdk}",
                "compileSdk" to report.compileSdk.takeIf { it > 0 }?.toString().orEmpty(),
            ),
        )
        Facts(
            stringResource(R.string.apk_contents),
            listOf(
                stringResource(R.string.apk_size) to report.fileBytes.readable(),
                "DEX" to "${report.dex.files} · ${report.dex.methods} methods",
                stringResource(R.string.apk_components) to with(report.components) {
                    "$activities · $services · $receivers · $providers"
                },
                "ABI" to report.abis.joinToString(", ").ifEmpty { "—" },
                stringResource(R.string.apk_permissions) to
                    "${report.permissions.size} (${report.dangerousPermissions.size})",
            ),
        )
        if (report.sizes.isNotEmpty()) {
            Facts(
                stringResource(R.string.apk_breakdown),
                report.sizes.map { it.label to it.bytes.readable() },
            )
        }
        report.signatureSha256?.let {
            Facts(stringResource(R.string.apk_signing), listOf(report.signatureScheme to ""))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 13.sp),
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Finding(text: String, blocking: Boolean, good: Boolean = false) {
    val accent = when {
        good -> oneDevsColors.live
        blocking -> oneDevsColors.critical
        else -> oneDevsColors.caution
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.tint)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(accent.solid),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = accent.solid,
        )
    }
}

@Composable
private fun Facts(title: String, rows: List<Pair<String, String>>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        rows.filter { it.first.isNotEmpty() }.forEach { (label, value) ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

/**
 * Bytes as a developer would say them. Binary units, because an APK's size is
 * measured against upload limits that are also binary.
 */
internal fun Long.readable(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024; unit++
    }
    return if (unit == 0) "$this B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
