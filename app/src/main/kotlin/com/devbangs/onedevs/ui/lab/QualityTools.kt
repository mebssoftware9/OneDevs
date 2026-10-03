package com.devbangs.onedevs.ui.lab

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Quality
import com.devbangs.onedevs.lab.verdict

// The tools that became possible once the analyzer read layouts, library
// versions and code markers. The testing ones pair what the APK says with the
// phone in hand: the check says what to look for, and a tap opens the setting
// that changes it.

/** A button to the system page where the thing being tested is switched. */
@Composable
internal fun OpenSettings(label: Int, action: String) {
    val context = LocalContext.current
    Pill(stringResource(label)) {
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
internal fun DependenciesTool(r: ApkReport) {
    CheckList(Quality.dependencies(r))
    Quality.dependencyGroups(r).forEach { (group, artifacts) ->
        ListCard(group, artifacts.map { (name, version) -> name to version })
    }
    if (r.libraries.isNotEmpty()) Note(stringResource(R.string.q_deps_note))
}

@Composable
internal fun IntegrityTool(r: ApkReport) {
    val checks = Quality.integrity(r)
    VerdictBanner(checks.verdict(), R.string.q_integrity_ready, R.string.q_integrity_warn, R.string.q_integrity_blocked)
    CheckList(checks)
}

@Composable
internal fun DarkModeTool(r: ApkReport) {
    val night = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    Facts(
        stringResource(R.string.lt_this_phone),
        listOf(stringResource(R.string.q_dark_now) to yesNo(night)),
    )
    CheckList(Quality.darkMode(r))
    OpenSettings(R.string.q_open_display, Settings.ACTION_DISPLAY_SETTINGS)
}

@Composable
internal fun AccessibilityTool(r: ApkReport) {
    CheckList(Quality.accessibility(r), sorted = false)
    r.layouts?.let {
        Facts(
            stringResource(R.string.q_layouts),
            listOf(
                stringResource(R.string.q_layouts_files) to it.files.grouped(),
                stringResource(R.string.q_layouts_images) to it.images.grouped(),
                stringResource(R.string.q_layouts_unlabelled) to it.unlabelledImages.grouped(),
                stringResource(R.string.q_layouts_small) to it.smallTargets.grouped(),
            ),
        )
    }
    OpenSettings(R.string.q_open_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

@Composable
internal fun FontScalingTool(r: ApkReport) {
    val configuration = LocalConfiguration.current
    Facts(
        stringResource(R.string.lt_this_phone),
        listOf(
            stringResource(R.string.q_font_scale) to "${configuration.fontScale}×",
            stringResource(R.string.lt_density) to "${configuration.densityDpi} dpi",
        ),
    )
    CheckList(Quality.fontScaling(r), sorted = false)
    OpenSettings(R.string.q_open_display, Settings.ACTION_DISPLAY_SETTINGS)
}

@Composable
internal fun OrientationTool(r: ApkReport) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Facts(
        stringResource(R.string.lt_this_phone),
        listOf(
            stringResource(R.string.q_orientation_now) to
                stringResource(if (landscape) R.string.q_landscape else R.string.q_portrait),
        ),
    )
    CheckList(Quality.orientation(r), sorted = false)
}

@Composable
internal fun BackgroundTool(r: ApkReport) {
    CheckList(Quality.background(r), sorted = false)
    OpenSettings(R.string.q_open_battery, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

@Composable
internal fun DataSafetyTool(r: ApkReport) {
    CheckList(Quality.dataSafetyChecks(r), sorted = false)
    val types = Quality.dataSafety(r)
    if (types.isEmpty()) return
    SectionTitle(stringResource(R.string.ds_sections))
    ListCard(
        stringResource(R.string.ds_sections_d),
        types.map { t -> stringResource(t.label) to t.because.map { it.resolve() }.joinToString(" · ") },
    )
    PrivacySdks(r)
}
