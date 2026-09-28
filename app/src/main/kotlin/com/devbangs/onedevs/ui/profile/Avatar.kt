package com.devbangs.onedevs.ui.profile

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
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
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(remote).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@withContext null
                file.writeBytes(bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                return@withContext null
            } finally {
                connection?.disconnect()
            }
        }
        BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }
