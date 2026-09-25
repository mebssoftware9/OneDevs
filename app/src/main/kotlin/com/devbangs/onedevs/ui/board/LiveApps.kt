package com.devbangs.onedevs.ui.board

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/**
 * An app published to Live Apps.
 *
 * Keyed by package name, which is the one piece of a listing that is stable,
 * unique and verifiable. Everything else here is what the developer said when
 * they listed it -- Play has no public API for reading a listing back, and the
 * only way to get one is to scrape the store page, which breaks Play's terms.
 * A platform whose whole subject is legitimate Play testing cannot be the thing
 * scraping Play.
 */
data class LiveApp(
    val packageName: String,
    /**
     * What the row calls it. Play listing names carry a keyword tail -- the
     * full one here is "Morpho: PDF & File Converter" -- and a 44dp row
     * ellipsises it halfway through the useful part. The listing keeps its
     * name; the row uses the one a person would say.
     */
    val name: String,
    val category: String,
    val developer: String,
    val reward: Int,
)

/**
 * Apps live on Google Play right now.
 *
 * Not debug-gated, unlike the board samples: these are real listings with real
 * package names, so the deep link works whether or not anything else does.
 */
internal val LiveApps = listOf(
    LiveApp("cc.devbangs.morpho", "Morpho", "Productivity", "DEVBANGS", 15),
    LiveApp("com.devbangs.search", "Search", "Tools", "DEVBANGS", 15),
    LiveApp("com.devbangs.beampad", "BeamPad", "Productivity", "DEVBANGS", 15),
)

/**
 * What the device already knows about an app, which is more than a listing
 * would tell us and costs nothing to ask for.
 *
 * PackageManager is the honest version of "fetch it from Play": for anything
 * installed it returns the real label, the real version and the real icon,
 * offline, with no API and no terms attached. For anything not installed it
 * returns null, and the developer's own description stands in.
 */
data class InstalledFacts(val versionName: String?, val installed: Boolean)

fun installedFacts(context: Context, packageName: String): InstalledFacts = try {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    InstalledFacts(info.versionName, true)
} catch (_: PackageManager.NameNotFoundException) {
    InstalledFacts(null, false)
}

/**
 * Opens the Play listing.
 *
 * market:// hands straight to the Play app when it is there, which is the
 * whole point on a device that has it. The https form is the fallback for a
 * device without Play services, where the store still opens in a browser.
 */
fun openPlayListing(context: Context, packageName: String) {
    val store = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        "https://play.google.com/store/apps/details?id=$packageName".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(store) }.onFailure { context.startActivity(web) }
}
