package com.devbangs.onedevs.ui.board

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens the Play listing.
 *
 * market:// hands straight to the Play app when it is there. The https form is
 * the fallback for a device without Play services, where the store still opens
 * in a browser.
 *
 * What used to live here -- a hardcoded list of three apps and a
 * PackageManager lookup for their icons -- is gone. The list was one
 * developer's own apps standing in for a board, and the lookup could only ever
 * have resolved for packages the manifest declared, while a tester browsing
 * the board has by definition installed none of them.
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
