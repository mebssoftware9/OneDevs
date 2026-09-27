package com.devbangs.onedevs.ui.lab

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A throwaway diagnostic, not a feature. It answers one question the Android
 * documentation does not: are UsageStatsManager results filtered by package
 * visibility on this device and OS version?
 *
 * The manifest already declares three packages in <queries>, which makes this a
 * controlled test rather than a fishing trip. If usage data is filtered, only
 * those three can appear. If anything else appears, it is not filtered.
 *
 * English only and no string resources on purpose: translating a diagnostic
 * into four locales would be work thrown away when this screen is deleted.
 */
private val DECLARED = setOf(
    "cc.devbangs.morpho",
    "com.devbangs.search",
    "com.devbangs.beampad",
)

private const val DAY_MS = 24L * 60L * 60L * 1000L

// The appop, not a runtime permission, so this is a query rather than a request.
// unsafeCheckOpNoThrow arrived in 29 and checkOpNoThrow was deprecated by it, so
// each is used only on the levels where it is the right call. The suppression is
// scoped to this function rather than the file.
@Suppress("DEPRECATION")
private fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } else {
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** A guess at which names are the platform's own, so the list reads usefully. */
private fun looksSystem(pkg: String): Boolean =
    pkg.startsWith("com.android.") || pkg.startsWith("com.google.") ||
        pkg.startsWith("android") || pkg.startsWith("com.samsung.") ||
        pkg.startsWith("com.sec.") || pkg.startsWith("com.miui.") ||
        pkg.startsWith("com.transsion.") || pkg.startsWith("com.mediatek.")

private data class Probe(
    val resumeEvents: Int,
    val fromEvents: Set<String>,
    val fromStats: Set<String>,
) {
    val all: Set<String> get() = fromEvents + fromStats
}

private suspend fun runProbe(context: Context): Probe = withContext(Dispatchers.IO) {
    val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val end = System.currentTimeMillis()
    val begin = end - DAY_MS

    val seen = mutableSetOf<String>()
    var resumes = 0
    val events = usage.queryEvents(begin, end)
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            seen += event.packageName
            resumes++
        }
    }

    // Both sources, because one can be filtered while the other is not.
    val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        .filter { it.totalTimeInForeground > 0L }
        .map { it.packageName }
        .toSet()

    Probe(resumes, seen, stats)
}

@Composable
fun UsageProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasUsageAccess(context)) }
    var probe by remember { mutableStateOf<Probe?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text("Usage access probe", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (granted) "Usage access: granted" else "Usage access: NOT granted",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }) { Text("Open usage access settings") }

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !running,
            onClick = {
                granted = hasUsageAccess(context)
                if (granted) {
                    scope.launch {
                        running = true
                        probe = runProbe(context)
                        running = false
                    }
                }
            },
        ) { Text(if (running) "Running..." else "Run probe") }

        val result = probe
        if (result != null) {
            val others = result.all.filterNot {
                it == context.packageName || it in DECLARED || looksSystem(it)
            }
            val declaredSeen = result.all.filter { it in DECLARED }

            Spacer(Modifier.height(18.dp))
            Text(
                text = when {
                    others.isNotEmpty() ->
                        "NOT FILTERED. Usage data covers packages this app never declared, " +
                            "so foreground time for any test app is observable."
                    declaredSeen.isNotEmpty() ->
                        "FILTERED to <queries>. Only declared packages came back, so this " +
                            "cannot observe an arbitrary test app."
                    else ->
                        "NO DATA. Grant access, open a few other apps, then run again."
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(12.dp))
            Text("resume events: ${result.resumeEvents}")
            Text("queryEvents packages: ${result.fromEvents.size}")
            Text("queryUsageStats packages: ${result.fromStats.size}")
            Text("declared seen: ${declaredSeen.size} of ${DECLARED.size} $declaredSeen")
            Text("undeclared non-system: ${others.size}")

            Spacer(Modifier.height(10.dp))
            others.take(30).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
