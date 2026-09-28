package com.devbangs.onedevs.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.devbangs.onedevs.data.images.cachedImage
import com.devbangs.onedevs.data.listings.Listing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A listing's icon, wherever it happens to live.
 *
 * Two sources, in order. The local file is a freshly picked image on the phone
 * that chose it, and only exists there. The URL is what everyone else loads,
 * and is the only one a listing read back from the server has -- which is why
 * three separate screens were showing a placeholder for apps that had icons:
 * each read the local path alone.
 *
 * One function, so the next screen that shows a listing cannot get it wrong in
 * a fourth way.
 */
@Composable
fun rememberListingIcon(listing: Listing): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(listing.id, listing.iconPath, listing.iconUrl) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(listing.id, listing.iconPath, listing.iconUrl) {
        icon = listing.iconPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        } ?: cachedImage(context, "icon-${listing.id}", listing.iconUrl)
    }
    return icon
}
