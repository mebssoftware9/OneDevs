package com.devbangs.onedevs.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Finding
import com.devbangs.onedevs.lab.Findings
import com.devbangs.onedevs.lab.Severity
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.util.Locale

/**
 * What one APK turned out to be, and what to do about it.
 *
 * Findings carry what was seen, what it costs and the next step, because a
 * report that only lists facts is a file viewer -- and a developer analysing
 * their own build already knows the facts. The facts stay underneath, for
 * checking the findings against.
 */
@Composable
fun ApkReportView(
    report: ApkReport,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val findings = Findings.of(report)
    // Read from the composition, not Locale.getDefault(): the grouping
    // separator in 120,710 differs by language, and a locale change has to
    // recompose this rather than leave a stale number on screen.
    val locale = LocalConfiguration.current.locales[0]
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
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

        if (findings.isEmpty()) {
            Clear()
        } else {
            findings.forEach { FindingCard(it) }
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
            buildList {
                add(stringResource(R.string.apk_size) to report.fileBytes.readable())
                add(stringResource(R.string.apk_dex) to "${report.dexEntries}")
                // The method count is only honest when every .dex entry parsed.
                // Where they did not, it describes the loader, and the finding
                // above says so rather than this row implying otherwise.
                if (Findings.methodCountIsMeaningful(report)) {
                    add(
                        stringResource(R.string.apk_methods) to
                            String.format(locale, "%,d", report.dex.methods),
                    )
                }
                // "2 · 0 · 1 · 1" is a number nobody can read. One row per
                // kind, and only the kinds this APK actually has.
                with(report.components) {
                    if (activities > 0) add(stringResource(R.string.comp_activities) to "$activities")
                    if (services > 0) add(stringResource(R.string.comp_services) to "$services")
                    if (receivers > 0) add(stringResource(R.string.comp_receivers) to "$receivers")
                    if (providers > 0) add(stringResource(R.string.comp_providers) to "$providers")
                }
                if (report.abis.isNotEmpty()) add("ABI" to report.abis.joinToString(", "))
            },
        )
        if (report.sizes.isNotEmpty()) {
            Facts(
                // Uncompressed, which is why the slices add up to more than the
                // file. It is the number that tells you what to cut; the one on
                // the line above is what the user downloads.
                stringResource(R.string.apk_breakdown_uncompressed),
                report.sizes.map { it.label to it.bytes.readable() },
            )
        }
        report.signatureSha256?.let { Fingerprint(it) }
    }
}

/** Nothing to report is a result, and it should look like one. */
@Composable
private fun Clear() {
    val accent = oneDevsColors.live
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.tint)
            .padding(14.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent.solid),
        )
        Text(
            text = stringResource(R.string.apk_clear),
            style = MaterialTheme.typography.bodySmall,
            color = accent.solid,
        )
    }
}

@Composable
private fun FindingCard(finding: Finding) {
    val scheme = MaterialTheme.colorScheme
    val accent: Accent = when (finding.severity) {
        Severity.Blocking -> oneDevsColors.critical
        Severity.Worth -> oneDevsColors.caution
        Severity.Note -> oneDevsColors.testing
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.tint)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    when (finding.severity) {
                        Severity.Blocking -> R.string.sev_blocking
                        Severity.Worth -> R.string.sev_worth
                        Severity.Note -> R.string.sev_note
                    },
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                fontWeight = FontWeight.Bold,
                color = accent.onSolid,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.solid)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = finding.what,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = finding.why,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = scheme.onSurfaceVariant,
        )
        if (finding.evidence.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = finding.evidence.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                fontWeight = FontWeight.Medium,
                color = accent.solid,
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = finding.action,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
    }
}

/**
 * The signing fingerprint, tappable.
 *
 * Its whole use is being compared against Play Console, and sixty-four
 * characters of hex is not something anyone retypes.
 */
@Composable
private fun Fingerprint(sha256: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha256))
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                text = stringResource(R.string.apk_signing),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.apk_copy),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = sha256,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 13.sp),
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.apk_signing_compare),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = scheme.onSurfaceVariant,
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
        rows.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.forEach { (label, value) ->
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
 * Bytes as a developer would say them. Binary units, because the limits an
 * APK is measured against are binary too.
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
