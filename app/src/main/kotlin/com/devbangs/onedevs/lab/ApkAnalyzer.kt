package com.devbangs.onedevs.lab

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Properties
import java.util.zip.ZipFile

/**
 * The launcher icon, rendered while the APK is still on disk.
 *
 * Kept apart from [ApkReport] because it is pixels, not facts: the report
 * stays a plain value that tests can build, and this carries the Bitmaps the
 * icon preview draws.
 */
class ApkIcon(
    val full: Bitmap,
    val adaptive: Boolean,
    val foreground: Bitmap?,
    val background: Bitmap?,
    val monochrome: Bitmap?,
    /** False on devices older than Android 13, which cannot say. */
    val monochromeKnown: Boolean,
)

/** One analysis: the report, and the icon when there is one. */
class Analysis(val report: ApkReport, val icon: ApkIcon?)

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

    /**
     * A DEX larger than this is read for its header only. Real ones are a
     * few megabytes; this is room for the largest and a ceiling on memory.
     */
    private const val MAX_DEX_READ = 64L * 1024 * 1024

    /** A manifest is kilobytes. Anything past this is not one. */
    private const val MAX_MANIFEST = 8L * 1024 * 1024

    private const val ICON_PX = 192
    private const val LAYER_PX = 288

    private const val METADATA = "META-INF/com/android/build/gradle/app-metadata.properties"

    suspend fun analyze(context: Context, uri: Uri): Result<Analysis> = runCatching {
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

    private fun read(context: Context, file: File, name: String): Analysis {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_CONFIGURATIONS or
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
        val layout = RandomAccessFile(file, "r").use { layout(Bytes.of(it)) }
        val scan = ZipFile(file).use { z -> scanZip(z) }
        val allItems = layout.items ?: scan.items
        val manifestBytes = scan.manifest
        val xml = manifestBytes?.let { BinaryXml.parse(it) }
        val facts = ManifestFacts.of(xml, info.packageName)
        val resolver = resolver(pm, app, info.packageName)
        val (marker, shape) = DexCode.merge(scan.code)
        val kinds = permissionKinds(pm, info, declared)

        val report = ApkReport(
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
            dangerousPermissions = declared.filter { kinds[it] == PermissionKind.Runtime }.sorted(),
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
            abis = scan.abis,
            nativeLibraries = scan.natives.size,
            dex = DexHeader.merge(scan.dex.map { it.counts }),
            dexEntries = scan.entries.count { it.first.endsWith(".dex") },
            sizes = sliceSizes(scan.entries),
            signatureSha256 = fingerprint(info),
            signatureScheme = scheme(info),
            testOnly = app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            largeHeap = app.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0,
            extractNativeLibs = app.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0,
            appClass = app.className,
            screens = ScreenSupport(
                small = app.flags and ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS != 0,
                normal = app.flags and ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS != 0,
                large = app.flags and ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS != 0,
                xlarge = app.flags and ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS != 0,
                anyDensity = app.flags and ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES != 0,
            ),
            features = info.reqFeatures.orEmpty().mapNotNull { f ->
                f.name?.let { Feature(it, f.flags and FeatureInfo.FLAG_REQUIRED != 0) }
            }.sortedBy { it.name },
            glEsVersion = info.reqFeatures.orEmpty()
                .filter { it.name == null && it.flags and FeatureInfo.FLAG_REQUIRED != 0 }
                .maxOfOrNull { it.reqGlEsVersion } ?: 0,
            componentList = components(info),
            permissionKinds = kinds,
            schemes = SigningBlock.schemes(layout.signingIds, allItems.map { it.name }),
            certificates = signers(info).orEmpty().mapNotNull { Certificates.of(it.toByteArray()) },
            natives = scan.natives.map { lib ->
                val item = allItems.firstOrNull { it.name == lib.path }
                val offset = layout.nativeOffsets[lib.path]
                lib.copy(
                    compressedBytes = item?.compressedSize ?: lib.bytes,
                    stored = item?.stored ?: false,
                    zip16k = if (item?.stored == true && offset != null) offset % Elf.PAGE_16K == 0L else null,
                )
            },
            items = allItems,
            dexFiles = scan.dex,
            compiler = marker,
            code = shape,
            manifest = facts,
            manifestXml = xml?.let { BinaryXml.render(it, BinaryXml.prefixes(manifestBytes), resolver) },
            buildMetadata = scan.metadata,
        )
        return Analysis(report, icon(pm, app))
    }

    /** What only the raw file layout can say. */
    private class Layout(
        /** Null when the directory could not be read, e.g. ZIP64. */
        val items: List<ZipItem>?,
        val signingIds: List<Int>,
        /** Where each stored library's bytes begin. */
        val nativeOffsets: Map<String, Long>,
    )

    private fun layout(src: Bytes): Layout {
        val directory = ApkZip.directory(src) ?: return Layout(null, emptyList(), emptyMap())
        val offsets = directory.items
            .filter { it.stored && it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .mapNotNull { item -> ApkZip.dataOffset(src, item)?.let { item.name to it } }
            .toMap()
        return Layout(directory.items, ApkZip.signingBlockIds(src, directory.offset), offsets)
    }

    /** Everything that needs the entries' bytes, in one pass over the ZIP. */
    private class Scan(
        val entries: List<Pair<String, Long>>,
        val items: List<ZipItem>,
        val dex: List<DexFile>,
        val code: List<DexCode.One>,
        val natives: List<NativeLib>,
        val abis: List<String>,
        val manifest: ByteArray?,
        val metadata: Map<String, String>,
    )

    private fun scanZip(z: ZipFile): Scan {
        val all = z.entries().asSequence().toList()
        val entries = all.map { it.name to it.size }
        val dex = mutableListOf<DexFile>()
        val code = mutableListOf<DexCode.One>()
        val natives = mutableListOf<NativeLib>()
        var manifest: ByteArray? = null
        var metadata: Map<String, String> = emptyMap()
        all.forEach { e ->
            when {
                e.name.endsWith(".dex") -> z.getInputStream(e).use { stream ->
                    // Small enough to hold whole: counts from the header and
                    // names from the rest. Otherwise the header alone.
                    val bytes = if (e.size in 0..MAX_DEX_READ) stream.readBytes() else head(stream, 0x70)
                    DexHeader.read(bytes)?.let { dex += DexFile(e.name, e.size, it) }
                    if (bytes.size > 0x70) DexCode.read(bytes)?.let { code += it }
                }
                e.name.startsWith("lib/") && e.name.endsWith(".so") -> {
                    val abi = e.name.removePrefix("lib/").substringBefore('/')
                    val elf = z.getInputStream(e).use { Elf.read(head(it, Elf.HEAD_BYTES)) }
                    natives += NativeLib(
                        path = e.name,
                        abi = abi,
                        bytes = e.size,
                        compressedBytes = e.compressedSize,
                        stored = e.method == java.util.zip.ZipEntry.STORED,
                        // Only 64-bit libraries can be loaded on a 16 KB page
                        // device, so only theirs is worth an answer.
                        elf16k = if (abi in NativeLib.ABI_64) elf?.supports16k else null,
                        zip16k = null,
                    )
                }
                e.name == "AndroidManifest.xml" && e.size in 0..MAX_MANIFEST ->
                    manifest = z.getInputStream(e).use { it.readBytes() }
                e.name == METADATA -> metadata = z.getInputStream(e).use { stream ->
                    Properties().apply { load(stream) }.let { p ->
                        p.stringPropertyNames().associateWith { p.getProperty(it).orEmpty() }
                    }
                }
            }
        }
        val abis = entries.mapNotNull { (n, _) ->
            n.removePrefix("lib/").takeIf { n.startsWith("lib/") && it.contains('/') }
                ?.substringBefore('/')
        }.distinct().sorted()
        return Scan(
            entries = entries,
            items = all.map { ZipItem(it.name, it.method, it.compressedSize, it.size, -1) },
            dex = dex.sortedWith(compareBy({ it.name.length }, { it.name })),
            code = code,
            natives = natives.sortedWith(compareBy({ it.abi }, { it.name })),
            abis = abis,
            manifest = manifest,
            metadata = metadata,
        )
    }

    /**
     * The launcher-visible pieces of each component, and its exported state
     * as the installer decided it -- which accounts for intent filters and
     * defaults the manifest does not spell out.
     */
    private fun components(info: PackageInfo): List<Component> = buildList {
        info.activities?.forEach {
            add(
                Component(
                    ComponentKind.Activity, it.name, it.exported, it.permission,
                    orientationLocked = it.screenOrientation in LOCKED_ORIENTATIONS,
                ),
            )
        }
        info.services?.forEach { add(Component(ComponentKind.Service, it.name, it.exported, it.permission)) }
        info.receivers?.forEach { add(Component(ComponentKind.Receiver, it.name, it.exported, it.permission)) }
        info.providers?.forEach {
            // A provider is guarded by its read and write permissions, not
            // by android:permission alone.
            val guard = it.readPermission ?: it.writePermission
            add(Component(ComponentKind.Provider, it.name, it.exported, guard, authority = it.authority))
        }
    }

    /** Orientations that fix the screen to portrait or to landscape. */
    private val LOCKED_ORIENTATIONS = setOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
    )

    /**
     * How each requested permission is granted. The device's own definitions
     * are used rather than a list kept here, which would go stale every
     * August; permissions the APK defines itself come from the APK.
     */
    private fun permissionKinds(
        pm: PackageManager,
        info: PackageInfo,
        declared: List<String>,
    ): Map<String, PermissionKind> {
        val own = info.permissions.orEmpty().associateBy { it.name }
        return declared.associateWith { name ->
            val permission = own[name] ?: runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            permission?.let(::kind) ?: PermissionKind.Unknown
        }
    }

    private fun kind(info: PermissionInfo): PermissionKind {
        // protectionLevel is deprecated from API 28 in favour of getProtection
        // and getProtectionFlags, but it still carries both, and minSdk here
        // is 26 where it is the only read. Masking is what getProtection does.
        @Suppress("DEPRECATION")
        val level = info.protectionLevel
        val base = level and PermissionInfo.PROTECTION_MASK_BASE
        return when {
            base == PermissionInfo.PROTECTION_DANGEROUS -> PermissionKind.Runtime
            level and PermissionInfo.PROTECTION_FLAG_APPOP != 0 -> PermissionKind.Special
            base == PermissionInfo.PROTECTION_NORMAL -> PermissionKind.Install
            // Signature, signatureOrSystem and internal (4, API 29) are all
            // out of reach of an app installed from Play.
            base == PermissionInfo.PROTECTION_SIGNATURE || base == 3 || base == 4 -> PermissionKind.Signature
            else -> PermissionKind.Unknown
        }
    }

    /**
     * Names a resource reference the way it was written, "@xml/backup_rules"
     * rather than @0x7f150002, using the APK's own resource table.
     */
    private fun resolver(pm: PackageManager, app: ApplicationInfo, pkg: String): (Int) -> String? {
        val resources = runCatching { pm.getResourcesForApplication(app) }.getOrNull()
        return { id ->
            runCatching { resources?.getResourceName(id) }.getOrNull()?.let { full ->
                val owner = full.substringBefore(':')
                val rest = full.substringAfter(':')
                if (owner == pkg) "@$rest" else "@$owner:$rest"
            }
        }
    }

    /**
     * The icon, drawn now because the file is deleted when this returns.
     * Null only when drawing fails; an APK with no icon of its own gets the
     * system's default, which is what a launcher would show too.
     */
    private fun icon(pm: PackageManager, app: ApplicationInfo): ApkIcon? = runCatching {
        val drawable = app.loadIcon(pm)
        val adaptive = drawable as? AdaptiveIconDrawable
        // The monochrome layer arrived with themed icons in Android 13. Below
        // that the drawable cannot say whether there is one.
        val monochrome = if (adaptive != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adaptive.monochrome
        } else {
            null
        }
        ApkIcon(
            full = drawable.render(ICON_PX),
            adaptive = adaptive != null,
            foreground = adaptive?.foreground?.render(LAYER_PX),
            background = adaptive?.background?.render(LAYER_PX),
            monochrome = monochrome?.render(LAYER_PX),
            monochromeKnown = adaptive == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
    }.getOrNull()

    private fun Drawable.render(px: Int): Bitmap = toBitmap(px, px, Bitmap.Config.ARGB_8888)

    private fun signers(info: PackageInfo): Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

    private fun fingerprint(info: PackageInfo): String? {
        val first = signers(info)?.firstOrNull() ?: return null
        return Certificates.fingerprint("SHA-256", first.toByteArray())
    }

    private fun scheme(info: PackageInfo): String = when {
        signers(info).isNullOrEmpty() -> "unsigned"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.signingInfo?.hasMultipleSigners() == true -> "multiple signers"
        else -> "v1 or later"
    }

    /** At most [length] bytes from the start of the stream. */
    private fun head(stream: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
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
