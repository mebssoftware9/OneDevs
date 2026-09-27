package com.devbangs.onedevs.ui.lab

import com.devbangs.onedevs.R
import com.devbangs.onedevs.ui.theme.Accent
import com.devbangs.onedevs.ui.theme.OneDevsColors

/**
 * Whether a tool runs today.
 *
 * [Soon] is not a promise about a date. It records that the tool is understood
 * and cannot be built yet, and the reason is always one of three: it needs the
 * source repository, it needs Play listing data, or Android stopped letting an
 * app observe that about another app.
 */
enum class LabStatus { Available, Soon }

/**
 * Tool names are not translated, and are plain strings rather than resources.
 *
 * "DEX Analysis", "ProGuard/R8 Check" and "Target SDK Checker" are the names
 * these things have in every language a developer works in -- translating them
 * would make the Lab harder to search, not easier to read. Ninety-six names
 * across four locales would also be nearly four hundred strings invented
 * without a translator. The layer names and the questions they answer are
 * translated, because those are prose.
 */
data class LabTool(val name: String, val status: LabStatus = LabStatus.Soon)

/** One of the seven layers, and the question it answers. */
data class LabLayer(
    val number: Int,
    val icon: Int,
    val name: Int,
    val question: Int,
    val accent: (OneDevsColors) -> Accent,
    val tools: List<LabTool>,
) {
    val available: Int get() = tools.count { it.status == LabStatus.Available }
}

private fun on(name: String) = LabTool(name, LabStatus.Available)

/**
 * The Lab, in the order the questions actually arrive: is the code good, did
 * it build, does it run, does it run everywhere, can it ship, is the listing
 * ready, can anyone find it.
 *
 * Twenty-eight of these run today and the rest are marked. What separates them
 * is not effort but access. Everything in layer two reads an APK, which is a
 * ZIP with a manifest Android will parse for us -- so signing, permissions,
 * components, native libraries, DEX counts and size breakdowns are all a file
 * away. Layer one needs the repository. Layers six and seven need Play listing
 * data, and the only way to get that is to scrape the store, which is the one
 * thing a Play-testing platform cannot be caught doing. Layer three is the sad
 * one: Android closed off watching another app's memory, CPU and crashes in
 * Oreo, and no permission reopens it.
 */
internal val LabCatalogue = listOf(
    LabLayer(
        number = 1,
        icon = R.drawable.ic_code,
        name = R.string.lab_l1,
        question = R.string.lab_l1_q,
        accent = { it.testing },
        tools = listOf(
            LabTool("Codebase Audit"), LabTool("Bug Detection"), LabTool("Security Analysis"),
            LabTool("Architecture Analysis"), LabTool("Performance Analysis"),
            LabTool("Memory & Resource Analysis"), LabTool("Concurrency & Async Analysis"),
            LabTool("Android Lifecycle Analysis"), LabTool("Dependency Analysis"),
            LabTool("Deprecated API Detection"), LabTool("Code Smell Detection"),
            LabTool("Dead Code Detection"), LabTool("Build Configuration Analysis"),
        ),
    ),
    LabLayer(
        number = 2,
        icon = R.drawable.ic_package,
        name = R.string.lab_l2,
        question = R.string.lab_l2_q,
        accent = { it.mission },
        tools = listOf(
            on("APK Analyzer"), LabTool("AAB Analyzer"), on("Manifest Inspector"),
            on("Permissions Inspector"), on("SDK Compatibility"), LabTool("Dependencies Inspector"),
            on("App Components"), on("Resource Inspector"), on("Native Libraries Inspector"),
            on("Certificate & Signing Inspector"), on("Build Configuration Inspector"),
            on("App Size Breakdown"), on("DEX Analysis"),
        ),
    ),
    LabLayer(
        number = 3,
        icon = R.drawable.ic_heartbeat,
        name = R.string.lab_l3,
        question = R.string.lab_l3_q,
        accent = { it.live },
        tools = listOf(
            LabTool("Crash Reports"), LabTool("ANR Detection"), LabTool("Performance Monitor"),
            LabTool("Memory Usage"), LabTool("Battery Impact"), LabTool("Startup Performance"),
            LabTool("App Size"), LabTool("CPU Usage"), LabTool("Network Performance"),
            LabTool("Rendering Performance"), LabTool("Background Activity"),
            LabTool("Error & Exception Analysis"),
        ),
    ),
    LabLayer(
        number = 4,
        icon = R.drawable.ic_device_mobile,
        name = R.string.lab_l4,
        question = R.string.lab_l4_q,
        accent = { it.caution },
        tools = listOf(
            on("Device Compatibility"), on("Android Version Testing"),
            on("Screen & Resolution Testing"), LabTool("Network Testing"),
            LabTool("Offline Testing"), on("Permission Testing"), LabTool("Dark Mode Testing"),
            LabTool("Accessibility Testing"), LabTool("Orientation Testing"),
            LabTool("Font & Display Scaling"), LabTool("Low-Memory Testing"),
            LabTool("Background/Foreground Testing"), on("Installation & Update Testing"),
        ),
    ),
    LabLayer(
        number = 5,
        icon = R.drawable.ic_shield_check,
        name = R.string.lab_l5,
        question = R.string.lab_l5_q,
        accent = { it.critical },
        tools = listOf(
            on("Release Readiness"), on("Version & Build Checker"), on("Signing Verification"),
            on("Target SDK Checker"), LabTool("App Bundle Validation"), on("Release Checklist"),
            LabTool("Pre-release Test"), on("Debug Build Detection"),
            on("Release Configuration Check"), on("ProGuard/R8 Check"),
            on("Backup Configuration Check"), on("Privacy Configuration Check"),
            LabTool("Play Integrity Readiness"), on("Pre-launch Risk Scan"),
        ),
    ),
    LabLayer(
        number = 6,
        icon = R.drawable.ic_storefront,
        name = R.string.lab_l6,
        question = R.string.lab_l6_q,
        accent = { it.community },
        tools = listOf(
            LabTool("Play Store Preview"), LabTool("Screenshot Preview"), on("Icon Preview"),
            LabTool("Listing Quality Check"), LabTool("Store Listing Checklist"),
            LabTool("Privacy Policy Check"), LabTool("App Content Check"),
            LabTool("Data Safety Check"), LabTool("Content Rating Check"),
            LabTool("Store Asset Validation"), LabTool("Feature Graphic Check"),
            LabTool("Listing Consistency Check"),
        ),
    ),
    LabLayer(
        number = 7,
        icon = R.drawable.ic_chart_line_up,
        name = R.string.lab_l7,
        question = R.string.lab_l7_q,
        accent = { it.feedback },
        tools = listOf(
            LabTool("Keyword Research"), LabTool("Search Volume"), LabTool("Keyword Difficulty"),
            LabTool("Keyword Intent"), LabTool("Keyword Coverage"), LabTool("Keyword Ranking Tracker"),
            LabTool("Competitor Keyword Analysis"), LabTool("Competitor Listing Analysis"),
            LabTool("Title Analyzer"), LabTool("Short Description Analyzer"),
            LabTool("Long Description Analyzer"), LabTool("Keyword Placement"),
            LabTool("Metadata Optimization"), LabTool("Screenshot Conversion Analysis"),
            LabTool("Icon Conversion Analysis"), LabTool("Store Listing Comparison"),
            LabTool("ASO Score"), LabTool("Ranking History"), LabTool("ASO Experiment Tracker"),
        ),
    ),
)
