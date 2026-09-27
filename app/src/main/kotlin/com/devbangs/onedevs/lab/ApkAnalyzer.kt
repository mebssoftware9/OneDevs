package com.devbangs.onedevs.lab

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Reads an APK the user picked and says what is in it.
 *
 * Everything here is local. PackageManager will parse a manifest out of any
 * APK on disk -- the same parser the installer uses, so the answers are the
 * ones the device would act on -- and the rest of the file is a ZIP.
 *
 * The one awkward step is the copy. getPackageArchiveInfo needs a filesystem
 * path, and the picker hands back a content URI that may point at Drive or a
 * USB stick, so the file is copied to the cache first and deleted after. That
 * is also the only way to be sure the bytes cannot change underneath a read
 * that takes several passes.
 */
object ApkAnalyzer {

    /** Anything larger is a mistake or an attack, not an APK someone tests. */
    private const val MAX_BYTES = 600L * 1024 * 1024

    suspend fun analyze(context: Context, uri: Uri): Result<ApkReport> = runCatching {
        val name = displayName(context, uri)
        val copy = File.createTempFile("inspect", ".apk", context.cacheDir)
        try {
            val copied = context.contentResolver.openInputStream(uri)
                ?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: error("could not open the file")
            require(copied <= MAX_BYTES) { "file is larger than 600 MB" }
            read(context, copy, name)
        } finally {
            copy.delete()
        }
    }

    private fun read(context: Context, file: File, name: String): ApkReport {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            // Signing moved to signingInfo in API 28; below that the only way
            // to see a certificate is the deprecated flag.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        val info = pm.getPackageArchiveInfo(file.path, flags)
            ?: error("this file is not an APK Android can parse")
        // getPackageArchiveInfo leaves these unset, and anything that reads
        // resources out of the package needs them pointing at the file itself.
        val app = info.applicationInfo?.apply {
            sourceDir = file.path
            publicSourceDir = file.path
        } ?: error("the APK has no application entry")

        val declared = info.requestedPermissions?.toList().orEmpty()
        val entries = ZipFile(file).use { z ->
            z.entries().asSequence().map { it.name to it.size }.toList()
        }
        val dex = ZipFile(file).use { z ->
            entries.filter { it.first.endsWith(".dex") }.mapNotNull { (entryName, _) ->
                z.getEntry(entryName)?.let { e ->
                    // Only the header is needed, so only the header is read.
                    // readNBytes is API 33 on Android whatever the JDK says,
                    // so the header is read the long way.
                    z.getInputStream(e).use { stream -> DexHeader.read(header(stream)) }
                }
            }
        }
        val abis = entries.mapNotNull { (n, _) ->
            n.removePrefix("lib/").takeIf { n.startsWith("lib/") && it.contains('/') }
                ?.substringBefore('/')
        }.distinct().sorted()

        return ApkReport(
            fileName = name,
            fileBytes = file.length(),
            packageName = info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            compileSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.compileSdkVersion else 0,
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            allowsBackup = app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
            allowsCleartext = app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
            permissions = declared.sorted(),
            dangerousPermissions = declared.filter { dangerous(pm, it) }.sorted(),
            components = Components(
                activities = info.activities?.size ?: 0,
                services = info.services?.size ?: 0,
                receivers = info.receivers?.size ?: 0,
                providers = info.providers?.size ?: 0,
                exported = buildList {
                    info.activities?.filter { it.exported }?.forEach { add(it.name) }
                    info.services?.filter { it.exported }?.forEach { add(it.name) }
                    info.receivers?.filter { it.exported }?.forEach { add(it.name) }
                    info.providers?.filter { it.exported }?.forEach { add(it.name) }
                }.sorted(),
            ),
            abis = abis,
            nativeLibraries = entries.count { it.first.startsWith("lib/") && it.first.endsWith(".so") },
            dex = DexHeader.merge(dex),
            sizes = sliceSizes(entries),
            signatureSha256 = fingerprint(info),
            signatureScheme = scheme(info),
        )
    }

    /**
     * Dangerous permissions are the ones the user is asked about at runtime.
     * The device's own definitions are used rather than a list kept here,
     * which would go stale every August.
     */
    private fun dangerous(pm: PackageManager, permission: String): Boolean = runCatching {
        val info = pm.getPermissionInfo(permission, 0)
        // getProtection arrived in API 28 and minSdk here is 26, so the older
        // read has to stay: protectionLevel packs flags above the base value,
        // and masking is what getProtection does internally anyway.
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            @Suppress("DEPRECATION")
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        level == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private fun signers(info: PackageInfo): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

    private fun fingerprint(info: PackageInfo): String? {
        val first = signers(info)?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }

    private fun scheme(info: PackageInfo): String = when {
        signers(info).isNullOrEmpty() -> "unsigned"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.signingInfo?.hasMultipleSigners() == true -> "multiple signers"
        else -> "v1 or later"
    }

    /** Exactly the header, or as much of it as the entry actually holds. */
    private fun header(stream: InputStream): ByteArray {
        val buffer = ByteArray(0x70)
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val column = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && c.moveToFirst()) c.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}
