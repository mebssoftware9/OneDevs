package com.devbangs.onedevs.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.Verdict
import com.devbangs.onedevs.lab.urgentFirst
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.oneDevsColors
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Resolves a sentence the rules chose into the reader's language.
 *
 * The rules name a resource and never see a Context; this is the only place
 * that knows what any of it says, which is why adding a language is a file of
 * strings rather than a change to the rules. Arguments that are themselves
 * [Msg]s -- a number, a date -- are resolved first, so they are grouped and
 * written the reader's way too.
 */
@Composable
internal fun Msg.resolve(): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (this) {
        is Msg.Raw -> text
        is Msg.Num -> NumberFormat.getIntegerInstance(locale).format(value)
        is Msg.Date -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))
        is Msg.Str -> stringResource(id, *args.map { if (it is Msg) it.resolve() else it }.toTypedArray())
        is Msg.Plural -> pluralStringResource(
            id,
            count,
            *args.map { if (it is Msg) it.resolve() else it }.toTypedArray(),
        )
    }
}

/** A number in the reader's grouping, for facts rows. */
@Composable
internal fun Long.grouped(): String = Msg.Num(this).resolve()

@Composable
internal fun Int.grouped(): String = Msg.Num(toLong()).resolve()

/**
 * The frame every tool but the APK Analyzer sits in: which tool, which file,
 * a way to another file and a way out.
 */
@Composable
internal fun ToolFrame(
    title: String,
    report: ApkReport,
    onAnother: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = report.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = "${report.packageName} · ${report.versionName} (${report.versionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Pill(stringResource(R.string.lab_close), onClose)
        }
        Pill(stringResource(R.string.lt_another), onAnother)
        content()
    }
}

@Composable
internal fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A heading between sections of one tool. */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A sentence of context under a heading. */
@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Status.accent(): Accent = when (this) {
    Status.Pass -> oneDevsColors.live
    Status.Info -> oneDevsColors.testing
    Status.Warn -> oneDevsColors.caution
    Status.Fail -> oneDevsColors.critical
}

/**
 * The mark beside a check. A shape of its own for each status as well as a
 * colour, so the difference survives a colour-blind reader, and a spoken
 * label for a screen reader.
 */
@Composable
internal fun StatusMark(status: Status) {
    val accent = status.accent()
    val label = stringResource(
        when (status) {
            Status.Pass -> R.string.st_pass
            Status.Info -> R.string.st_info
            Status.Warn -> R.string.st_warn
            Status.Fail -> R.string.st_fail
        },
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(accent.solid)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = when (status) {
                Status.Pass -> "✓"
                Status.Info -> "i"
                Status.Warn -> "!"
                Status.Fail -> "✕"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 10.sp),
            fontWeight = FontWeight.Bold,
            color = accent.onSolid,
        )
    }
}

/** Checks, most urgent first, in one bordered card. */
@Composable
internal fun CheckList(checks: List<Check>, sorted: Boolean = true) {
    if (checks.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        (if (sorted) checks.urgentFirst() else checks).forEach { CheckRow(it) }
    }
}

@Composable
private fun CheckRow(check: Check) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        StatusMark(check.status)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = check.title.resolve(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            check.detail?.let {
                Text(
                    text = it.resolve(),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (check.evidence.isNotEmpty()) {
                Text(
                    text = check.evidence.map { it.resolve() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                    fontWeight = FontWeight.Medium,
                    color = check.status.accent().solid,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** A one-line answer at the top of a tool that has one. */
@Composable
internal fun VerdictBanner(verdict: Verdict, ready: Int, warnings: Int, blocked: Int) {
    val accent = when (verdict) {
        Verdict.Ready -> oneDevsColors.live
        Verdict.Warnings -> oneDevsColors.caution
        Verdict.Blocked -> oneDevsColors.critical
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.tint)
            .padding(14.dp),
    ) {
        StatusMark(
            when (verdict) {
                Verdict.Ready -> Status.Pass
                Verdict.Warnings -> Status.Warn
                Verdict.Blocked -> Status.Fail
            },
        )
        Text(
            text = stringResource(
                when (verdict) {
                    Verdict.Ready -> ready
                    Verdict.Warnings -> warnings
                    Verdict.Blocked -> blocked
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent.solid,
        )
    }
}

/**
 * Text meant to be read as code: a manifest, a fingerprint. Scrolls sideways
 * rather than wrapping, because a wrapped attribute is a misread one, and
 * copies on tap, because nobody retypes it.
 */
@Composable
internal fun CodeBlock(title: String, code: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                text = title,
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
                modifier = Modifier.clickable {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, code))
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            ),
            color = scheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/** A value worth copying, like a certificate fingerprint. */
@Composable
internal fun Copyable(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(label, value))
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                text = label,
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
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 13.sp),
            color = scheme.onSurface,
        )
    }
}

/**
 * Named things with a line of detail each: permissions, components, files.
 * Unlike facts, a row with no detail still shows -- the name is the point.
 */
@Composable
internal fun ListCard(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
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
        rows.forEach { (name, detail) ->
            Column(Modifier.padding(top = 6.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Plain words where a tool has nothing to list. */
@Composable
internal fun Empty(text: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    )
}

/** "Yes" or "No" in the reader's language. */
@Composable
internal fun yesNo(value: Boolean): String = stringResource(if (value) R.string.lt_yes else R.string.lt_no)
