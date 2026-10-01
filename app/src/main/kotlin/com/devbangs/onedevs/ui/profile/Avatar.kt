package com.devbangs.onedevs.ui.profile

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.devbangs.onedevs.data.images.decodeBounded
import com.devbangs.onedevs.data.images.download
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The signed-in developer's Google picture, fetched once and kept.
 *
 * By hand rather than through an image library, because this is the only
 * remote image OneDevs shows and a caching stack for one avatar would be more
 * code than this, plus a dependency, in an app that tells testers how large
 * things are before they install them.
 *
 * Named by user id, so signing in as someone else does not show the last
 * person's face. A failure of any kind returns null and the card falls back to
 * an initial, which is a perfectly good avatar.
 */
suspend fun loadAvatar(context: Context, userId: String, url: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "avatar-$userId.png")
        if (!file.exists()) {
            val remote = url?.takeIf { it.isNotBlank() } ?: return@withContext null
            if (!download(remote, file)) return@withContext null
        }
        decodeBounded(file)
    }
