package com.devbangs.onedevs.ui.lab

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.devbangs.onedevs.data.usage.hasUsageAccess
import com.devbangs.onedevs.data.usage.usageAccessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The measurement the mission evidence rests on, kept runnable.
 *
 * The Android documentation does not say whether usage events are filtered by
 * package visibility. This asked a real device, and the answer -- that they are
 * not -- is what makes a thirty-two second check possible without
 * QUERY_ALL_PACKAGES, which Play would never grant a testing platform.
 *
 * The manifest declares three packages in <queries>, which makes this a
 * controlled test rather than a fishing trip: filtered would mean only those
 * three can appear. Debug only, and worth re-running on every new phone.
 */
private val DECLARED = setOf(
    "cc.devbangs.morpho",
    "com.devbangs.search",
    "com.devbangs.beampad",
)

private fun looksSystem(pkg: String): Boolean =
    pkg.startsWith("com.android.") || pkg.startsWith("com.google.") ||
        pkg.startsWith("android") || pkg.startsWith("com.samsung.") ||
        pkg.startsWith("com.sec.") || pkg.startsWith("com.miui.") ||
        pkg.startsWith("com.transsion.") || pkg.startsWith("com.mediatek.")

private suspend fun surveyPackages(context: Context): Set<String> =
    withContext(Dispatchers.IO) {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 24L * 60 * 60 * 1000, end)
        val event = UsageEvents.Event()
        val seen = mutableSetOf<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) seen += event.packageName
        }
        seen
    }

@Composable
fun UsageProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasUsageAccess(context)) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Usage access probe", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (granted) "granted" else "NOT granted",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { context.startActivity(usageAccessSettings()) }) {
            Text("Open usage access settings")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !running,
            onClick = {
                granted = hasUsageAccess(context)
                if (granted) {
                    scope.launch {
                        running = true
                        val seen = surveyPackages(context)
                        val declared = seen.filter { it in DECLARED }
                        val others = seen.filterNot {
                            it == context.packageName || it in DECLARED || looksSystem(it)
                        }
                        verdict = when {
                            others.isNotEmpty() ->
                                "NOT FILTERED. ${others.size} undeclared non-system packages: " +
                                    others.take(8).joinToString(", ")
                            declared.isNotEmpty() ->
                                "FILTERED to <queries>. Only declared packages came back."
                            else -> "NO DATA. Use a few other apps, then run again."
                        }
                        running = false
                    }
                }
            },
        ) { Text(if (running) "Running..." else "Run probe") }

        verdict?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
    }
}
