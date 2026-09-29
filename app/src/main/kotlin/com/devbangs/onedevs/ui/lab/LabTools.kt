package com.devbangs.onedevs.ui.lab

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.devbangs.onedevs.BuildConfig
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.Analysis
import com.devbangs.onedevs.lab.ApkAnalyzer
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.DeviceProfile
import com.devbangs.onedevs.ui.theme.oneDevsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every tool that runs, by the name the catalogue gives it.
 *
 * The catalogue marks what can be built; this is what has been. A test holds
 * the two together, so a tool marked available that nobody wired up fails the
 * build instead of sitting in the list as a row that does nothing.
 */
internal enum class ToolKind(val toolName: String) {
    Analyzer("APK Analyzer"),
    Manifest("Manifest Inspector"),
    Permissions("Permissions Inspector"),
    Sdk("SDK Compatibility"),
    Components("App Components"),
    Resources("Resource Inspector"),
    Natives("Native Libraries Inspector"),
    Certificates("Certificate & Signing Inspector"),
    BuildInspector("Build Configuration Inspector"),
    Size("App Size Breakdown"),
    Dex("DEX Analysis"),
    Device("Device Compatibility"),
    Versions("Android Version Testing"),
    Screens("Screen & Resolution Testing"),
    PermissionTesting("Permission Testing"),
    Install("Installation & Update Testing"),
    Readiness("Release Readiness"),
    Version("Version & Build Checker"),
    Signing("Signing Verification"),
    Target("Target SDK Checker"),
    Checklist("Release Checklist"),
    Debug("Debug Build Detection"),
    ReleaseConfig("Release Configuration Check"),
    Proguard("ProGuard/R8 Check"),
    Backup("Backup Configuration Check"),
    Privacy("Privacy Configuration Check"),
    Prelaunch("Pre-launch Risk Scan"),
    Icon("Icon Preview"),
    ;

    companion object {
        fun of(name: String): ToolKind? = entries.firstOrNull { it.toolName == name }
    }
}

/** What the picker offers. Some file managers only know an APK as bytes. */
private val APK_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")

/**
 * The Lab: seven layers of tools, and whichever tool is open.
 *
 * One APK serves every tool. Opening a second tool after the first does not
 * ask for the file again -- the analysis is the expensive part and the tools
 * are different questions asked of it -- and "Another APK" is always one tap
 * away for when the question is about a different file.
 */
@Composable
fun LabHome(modifier: Modifier = Modifier) {
    // One layer open at a time. Seven cards all open is the wall of ninety-six
    // names the closed state exists to avoid.
    var open by rememberSaveable { mutableIntStateOf(-1) }
    var tool by remember { mutableStateOf<ToolKind?>(null) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var installed by remember { mutableStateOf<ApkReport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var installedFailure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Keyed on the configuration so a rotation or a resize re-reads the
    // screen: the testing tools compare against it.
    val configuration = LocalConfiguration.current
    val device = remember(configuration) { DeviceProfile.current(context) }
    val scope = rememberCoroutineScope()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            // Backed out of the picker before any APK was read: there is
            // nothing for the tool to show, so go back to the list.
            if (analysis == null) tool = null
            return@rememberLauncherForActivityResult
        }
        failure = null
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { ApkAnalyzer.analyze(context, uri) }
            working = false
            result.onSuccess {
                analysis = it
                // The earlier version was chosen against the old file.
                installed = null
                installedFailure = null
            }.onFailure {
                failure = it.message
                tool = null
            }
        }
    }
    val pickInstalled = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installedFailure = null
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { ApkAnalyzer.analyze(context, uri) }
            working = false
            result.onSuccess { installed = it.report }.onFailure { installedFailure = it.message }
        }
    }

    BackHandler(enabled = tool != null) { tool = null }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        val current = analysis
        val kind = tool
        if (kind != null && current != null && !working) {
            if (kind == ToolKind.Analyzer) {
                Pill(stringResource(R.string.lt_another)) { pick.launch(APK_TYPES) }
                ApkReportView(report = current.report, onClose = { tool = null })
            } else {
                ToolFrame(
                    title = kind.toolName,
                    report = current.report,
                    onAnother = { pick.launch(APK_TYPES) },
                    onClose = { tool = null },
                ) {
                    ToolView(
                        kind = kind,
                        analysis = current,
                        device = device,
                        installed = installed,
                        installedFailure = installedFailure,
                        onPickInstalled = { pickInstalled.launch(APK_TYPES) },
                    )
                }
            }
            return@Column
        }
        Text(
            text = stringResource(R.string.lab_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (working) {
            Text(
                text = stringResource(R.string.lab_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        failure?.let {
            Text(
                text = stringResource(R.string.lab_failed, it),
                style = MaterialTheme.typography.bodySmall,
                color = oneDevsColors.critical.solid,
            )
        }
        // The probe keeps its finding runnable, in debug only. It measured
        // something the documentation does not state and the answer was not
        // the one assumed, so it earns the right to be re-run on the next
        // phone rather than being deleted into a commit message.
        if (BuildConfig.DEBUG) {
            UsageProbeScreen()
        }
        LabCatalogue.forEach { layer ->
            LabLayerCard(
                layer = layer,
                expanded = open == layer.number,
                onToggle = { open = if (open == layer.number) -1 else layer.number },
                runnable = { ToolKind.of(it.name) != null },
                onTool = { chosen ->
                    tool = ToolKind.of(chosen.name)
                    if (analysis == null) pick.launch(APK_TYPES)
                },
            )
        }
    }
}

@Composable
private fun ToolView(
    kind: ToolKind,
    analysis: Analysis,
    device: DeviceProfile,
    installed: ApkReport?,
    installedFailure: String?,
    onPickInstalled: () -> Unit,
) {
    val r = analysis.report
    when (kind) {
        // Shown by LabHome with its own header, never through here.
        ToolKind.Analyzer -> Unit
        ToolKind.Manifest -> ManifestTool(r)
        ToolKind.Permissions -> PermissionsTool(r)
        ToolKind.Sdk -> SdkTool(r, device)
        ToolKind.Components -> ComponentsTool(r)
        ToolKind.Resources -> ResourcesTool(r)
        ToolKind.Natives -> NativesTool(r)
        ToolKind.Certificates -> CertificatesTool(r)
        ToolKind.BuildInspector -> BuildConfigTool(r)
        ToolKind.Size -> SizeTool(r)
        ToolKind.Dex -> DexTool(r)
        ToolKind.Device -> DeviceCompatTool(r, device)
        ToolKind.Versions -> VersionsTool(r, device)
        ToolKind.Screens -> ScreensTool(r, device)
        ToolKind.PermissionTesting -> PermissionTestingTool(r, device)
        ToolKind.Install -> InstallTool(r, device, installed, installedFailure, onPickInstalled)
        ToolKind.Readiness -> ReadinessTool(r)
        ToolKind.Version -> VersionTool(r)
        ToolKind.Signing -> SigningTool(r)
        ToolKind.Target -> TargetTool(r)
        ToolKind.Checklist -> ChecklistTool(r)
        ToolKind.Debug -> DebugTool(r)
        ToolKind.ReleaseConfig -> ReleaseConfigTool(r)
        ToolKind.Proguard -> ProguardTool(r)
        ToolKind.Backup -> BackupTool(r)
        ToolKind.Privacy -> PrivacyTool(r)
        ToolKind.Prelaunch -> PrelaunchTool(r)
        ToolKind.Icon -> IconTool(analysis.icon)
    }
}
