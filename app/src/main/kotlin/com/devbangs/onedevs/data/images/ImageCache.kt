package com.devbangs.onedevs.data.images

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
 * Remote images, fetched once and kept on disk.
 *
 * By hand rather than through an image library. OneDevs shows two kinds of
 * remote image -- an avatar and an app icon -- and a caching stack for two
 * would be more code than this, plus a dependency, in an app that tells
 * testers how large things are before they install them.
 *
 * Keyed by caller-chosen name, so a listing's icon changes when the listing
 * does and one developer's avatar never stands in for another's. Any failure
 * returns null and the caller shows whatever it shows without an icon.
 */
suspend fun cachedImage(context: Context, key: String, url: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val safe = key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(context.cacheDir, "img-$safe.png")
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
