package com.devbangs.onedevs.lab

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads an .aab the user picked.
 *
 * Copied to the cache first, as an APK is, so a file on Drive or a USB stick
 * can be read as a ZipFile and cannot change between passes. Nothing is
 * installed or uploaded: a bundle is a ZIP, and [Bundles] reads it.
 */
object BundleFile {

    /** A bundle is bigger than its APKs, but not this big. */
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** Only manifests, signature blocks and DEX are read whole, and none come near this. */
    private const val MAX_READ = 64L * 1024 * 1024

    suspend fun analyze(context: Context, uri: Uri): Result<BundleReport> = runCatching {
        val name = displayName(context, uri)
        val copy = File.createTempFile("inspect", ".aab", context.cacheDir)
        try {
            val copied = context.contentResolver.openInputStream(uri)
                ?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: error("could not open the file")
            require(copied <= MAX_BYTES) { "file is larger than 2 GB" }
            ZipFile(copy).use { zip ->
                val all = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                require(all.any { it.name == "base/manifest/AndroidManifest.xml" || it.name == "BundleConfig.pb" }) {
                    "this file is not an Android App Bundle"
                }
                Bundles.read(
                    fileName = name,
                    fileBytes = copy.length(),
                    entries = all.map { Bundles.Entry(it.name, it.size, it.compressedSize) },
                    bytesOf = { path ->
                        zip.getEntry(path)?.takeIf { it.size in 0..MAX_READ }
                            ?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                    },
                    elfHead = { path ->
                        zip.getEntry(path)?.let { e ->
                            zip.getInputStream(e).use { stream ->
                                val buffer = ByteArray(Elf.HEAD_BYTES)
                                var filled = 0
                                while (filled < buffer.size) {
                                    val read = stream.read(buffer, filled, buffer.size - filled)
                                    if (read <= 0) break
                                    filled += read
                                }
                                buffer.copyOf(filled)
                            }
                        }
                    },
                )
            }
        } finally {
            copy.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && c.moveToFirst()) c.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}
