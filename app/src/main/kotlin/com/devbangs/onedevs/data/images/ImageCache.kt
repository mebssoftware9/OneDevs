package com.devbangs.onedevs.data.images

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.devbangs.onedevs.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Larger than any icon or avatar; anything bigger is not one. */
internal const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

/** An icon is 512 px a side. Twice that again is still a picture; past it, a trap. */
internal const val MAX_IMAGE_SIDE = 4096

private val ownHost: String =
    runCatching { URI(BuildConfig.SUPABASE_URL).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()

/**
 * Whether OneDevs fetches an image from [url]: https, from this project's own
 * storage or from Google's account pictures, and nowhere else.
 *
 * A listing's icon address was once whatever its developer typed, so every
 * phone showing the listing fetched whatever that address served. The server
 * now refuses such addresses; this refuses them again for anything on file.
 */
internal fun imageAllowed(url: String, own: String = ownHost): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return (own.isNotEmpty() && host == own) || host.endsWith(".googleusercontent.com")
}

/**
 * Downloads [url] into [file], refusing any address [imageAllowed] refuses,
 * any redirect, and anything over [MAX_IMAGE_BYTES] however the server sizes
 * it. Writes to a side file first, so a half-read image is never kept.
 */
internal fun download(url: String, file: File): Boolean {
    if (!imageAllowed(url)) return false
    val part = File(file.parentFile, file.name + ".part")
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url.trim()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
        if (connection.contentLengthLong > MAX_IMAGE_BYTES) return false
        var total = 0L
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) return false
                    output.write(buffer, 0, read)
                }
            }
        }
        total > 0 && part.renameTo(file)
    } catch (e: IOException) {
        false
    } finally {
        connection?.disconnect()
        part.delete()
    }
}

/**
 * Decodes [file] at no more than [maxSide] px on its longer side, after
 * reading its size alone. A file that is not an image, or claims dimensions
 * no icon has, is deleted rather than kept to fail again on every screen.
 */
internal fun decodeBounded(file: File, maxSide: Int = 512): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longer = maxOf(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || longer > MAX_IMAGE_SIDE) {
        file.delete()
        return null
    }
    var sample = 1
    while (longer / (sample * 2) >= maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    if (bitmap == null) {
        file.delete()
        return null
    }
    return bitmap.asImageBitmap()
}

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
            if (!download(remote, file)) return@withContext null
        }
        decodeBounded(file)
    }
