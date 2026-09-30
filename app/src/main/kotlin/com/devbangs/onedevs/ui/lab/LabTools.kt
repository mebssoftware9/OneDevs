package com.devbangs.onedevs.ui.lab

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
/** What a tool reads: an APK, images from the phone, or the listing's text. */
internal enum class ToolInput { Apk, Images, Text }

internal enum class ToolKind(val toolName: String, val input: ToolInput = ToolInput.Apk) {
    Analyzer("APK Analyzer"),
    Manifest("Manifest Inspector"),
    Permissions("Permissions Inspector"),
    Sdk("SDK Compatibility"),
    Dependencies("Dependencies Inspector"),
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
    DarkMode("Dark Mode Testing"),
    Accessibility("Accessibility Testing"),
    Orientation("Orientation Testing"),
    FontScaling("Font & Display Scaling"),
    Background("Background/Foreground Testing"),
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
    Integrity("Play Integrity Readiness"),
    Prelaunch("Pre-launch Risk Scan"),
    Screenshots("Screenshot Preview", ToolInput.Images),
    Icon("Icon Preview"),
    ListingQuality("Listing Quality Check", ToolInput.Text),
    ListingChecklist("Store Listing Checklist", ToolInput.Text),
    DataSafety("Data Safety Check"),
    StoreAssets("Store Asset Validation", ToolInput.Images),
    FeatureGraphic("Feature Graphic Check", ToolInput.Images),
    KeywordCoverage("Keyword Coverage", ToolInput.Text),
    Title("Title Analyzer", ToolInput.Text),
    ShortDescription("Short Description Analyzer", ToolInput.Text),
    LongDescription("Long Description Analyzer", ToolInput.Text),
    KeywordPlacement("Keyword Placement", ToolInput.Text),
    Metadata("Metadata Optimization", ToolInput.Text),
    AsoScore("ASO Score", ToolInput.Text),
    ;

    companion object {
        fun of(name: String): ToolKind? = entries.firstOrNull { it.toolName == name }
    }
}

/**
 * The first card at the top, the last at the bottom, the rest evenly between
 * -- but never closer than 8dp, so an opened layer that overflows the screen
 * still reads as separate cards rather than one block.
 */
private val SpreadAtLeast8 = object : Arrangement.Vertical {
    override val spacing = 8.dp

    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val least = 8.dp.roundToPx()
        val gap = if (sizes.size > 1) {
            maxOf(least, (totalSize - sizes.sum()) / (sizes.size - 1))
        } else {
            0
        }
        var y = 0
        sizes.forEachIndexed { i, height ->
            outPositions[i] = y
            y += height + gap
        }
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
fun LabHome(onPlans: () -> Unit, modifier: Modifier = Modifier) {
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
    val app = context.applicationContext as com.devbangs.onedevs.OneDevsApplication
    val plan by app.plans.plan.collectAsState()
    // Set when a full Lab is asked to analyse one more app: the sheet that
    // explains it, rather than an error.
    var full by remember { mutableStateOf<com.devbangs.onedevs.data.plans.LabClaim.Upgrade?>(null) }
    var planUnknown by remember { mutableStateOf(false) }
    // Keyed on the configuration so a rotation or a resize re-reads the
    // screen: the testing tools compare against it.
    val configuration = LocalConfiguration.current
    val device = remember(configuration) { DeviceProfile.current(context) }
    val scope = rememberCoroutineScope()
    // Graphics picked per tool, and the listing every text tool shares. The
    // listing is kept on the phone so it is there the next time too.
    var images by remember { mutableStateOf<Map<ToolKind, List<PickedImage>>>(emptyMap()) }
    var listing by remember { mutableStateOf(ListingDraft.load(context)) }
    var keywords by remember { mutableStateOf(ListingDraft.keywordText(context)) }

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
            // Which app this is decides whether a free Lab may show it, so
            // the claim is asked before anything of the report appears.
            val claim = result.getOrNull()?.let { app.plans.claimLabApp(it.report.packageName) }
            working = false
            planUnknown = false
            result.onSuccess {
                when (claim) {
                    is com.devbangs.onedevs.data.plans.LabClaim.Upgrade -> {
                        full = claim
                        if (analysis == null) tool = null
                    }
                    com.devbangs.onedevs.data.plans.LabClaim.Unknown -> {
                        planUnknown = true
                        if (analysis == null) tool = null
                    }
                    else -> {
                        analysis = it
                        // The earlier version was chosen against the old file.
                        installed = null
                        installedFailure = null
                    }
                }
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

    // The viewport's height, and the header's, so the seven cards can share
    // what is left of the screen instead of bunching at the top of it.
    var viewport by remember { mutableIntStateOf(0) }
    var header by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.height }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
    ) {
        val current = analysis
        val kind = tool
        if (kind != null && kind.input != ToolInput.Apk) {
            // Images and text are chosen inside the tool, so there is no APK
            // to wait for and no "Another APK" to offer.
            PlainFrame(title = kind.toolName, onClose = { tool = null }) {
                if (kind.input == ToolInput.Images) {
                    val onImages: (List<PickedImage>) -> Unit = { images = images + (kind to it) }
                    when (kind) {
                        ToolKind.FeatureGraphic -> FeatureGraphicTool(images[kind].orEmpty(), onImages)
                        ToolKind.Screenshots -> ScreenshotsTool(images[kind].orEmpty(), onImages)
                        else -> StoreAssetsTool(images[kind].orEmpty(), onImages)
                    }
                } else {
                    ListingInput(listing, keywords) { changed, text ->
                        listing = changed
                        keywords = text
                        ListingDraft.save(context, changed, text)
                    }
                    ListingTool(kind, listing)
                }
            }
            return@Column
        }
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
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.onSizeChanged { header = it.height },
        ) {
            Text(
                text = stringResource(R.string.lab_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
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
            if (planUnknown) {
                Text(
                    text = stringResource(R.string.lab_plan_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = oneDevsColors.critical.solid,
                )
            }
            com.devbangs.onedevs.ui.plans.LabAppCard(
                current = analysis?.report?.packageName ?: plan?.labApp,
                tier = plan?.tier ?: com.devbangs.onedevs.data.plans.Tier.Community,
                onChange = { pick.launch(APK_TYPES) },
            )
        }
        // Everything below the header, less the padding and the one gap
        // between them. A layer opened past that height scrolls as before.
        val room = with(density) { (viewport - header).toDp() } - 28.dp
        Column(
            verticalArrangement = SpreadAtLeast8,
            modifier = Modifier.heightIn(min = room.coerceAtLeast(0.dp)),
        ) {
            LabCatalogue.forEach { layer ->
                LabLayerCard(
                    layer = layer,
                    expanded = open == layer.number,
                    onToggle = { open = if (open == layer.number) -1 else layer.number },
                    runnable = { ToolKind.of(it.name) != null },
                    onTool = { chosen ->
                        val next = ToolKind.of(chosen.name)
                        tool = next
                        if (next?.input == ToolInput.Apk && analysis == null) pick.launch(APK_TYPES)
                    },
                )
            }
        }
        // Room to grow while the Lab has a limit; a Pro Lab has none.
        if (plan?.labLimit != null || plan == null) {
            com.devbangs.onedevs.ui.plans.UpgradeBanner(onClick = onPlans)
        }
    }
    full?.let { claim ->
        com.devbangs.onedevs.ui.plans.UpgradeSheet(
            kept = claim.kept,
            limit = claim.limit,
            onUpgrade = {
                full = null
                onPlans()
            },
            onDismiss = { full = null },
        )
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
        ToolKind.Dependencies -> DependenciesTool(r)
        ToolKind.DarkMode -> DarkModeTool(r)
        ToolKind.Accessibility -> AccessibilityTool(r)
        ToolKind.Orientation -> OrientationTool(r)
        ToolKind.FontScaling -> FontScalingTool(r)
        ToolKind.Background -> BackgroundTool(r)
        ToolKind.Integrity -> IntegrityTool(r)
        ToolKind.DataSafety -> DataSafetyTool(r)
        // Opened by LabHome in a plain frame: they read no APK.
        else -> Unit
    }
}
