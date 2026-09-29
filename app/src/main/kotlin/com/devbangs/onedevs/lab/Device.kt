package com.devbangs.onedevs.lab

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants

/**
 * The phone the Lab is running on, as far as installing an app is concerned.
 *
 * The testing layer's tools compare an APK against this. It is a snapshot of
 * plain values rather than a live view of the system, so every rule that reads
 * it is a pure function and can be tested with a device that does not exist.
 */
data class DeviceProfile(
    val api: Int,
    val release: String,
    val model: String,
    val abis: List<String>,
    val features: Set<String>,
    /** Packed as the platform packs it: 0x00030002 is 3.2. */
    val glEs: Int,
    val pageSize: Long,
    val densityDpi: Int,
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
    val freeBytes: Long,
) {
    /** The resource qualifier Android picks drawables from on this screen. */
    val densityBucket: String get() = bucket(densityDpi)

    companion object {
        fun current(context: Context): DeviceProfile {
            val config = context.resources.configuration
            val am = context.getSystemService(ActivityManager::class.java)
            return DeviceProfile(
                api = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE.orEmpty(),
                model = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { !it.isNullOrBlank() }.distinct().joinToString(" "),
                abis = Build.SUPPORTED_ABIS.toList(),
                features = context.packageManager.systemAvailableFeatures
                    .mapNotNull { it.name }.toSet(),
                glEs = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0,
                pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L),
                densityDpi = config.densityDpi,
                widthDp = config.screenWidthDp,
                heightDp = config.screenHeightDp,
                smallestWidthDp = config.smallestScreenWidthDp,
                freeBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L),
            )
        }

        /** Android's density buckets, by the upper edge of each. */
        fun bucket(dpi: Int): String = when {
            dpi <= 120 -> "ldpi"
            dpi <= 160 -> "mdpi"
            dpi <= 213 -> "tvdpi"
            dpi <= 240 -> "hdpi"
            dpi <= 320 -> "xhdpi"
            dpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }

        /** "3.2" from 0x00030002. */
        fun glEsName(packed: Int): String = "${packed shr 16}.${packed and 0xFFFF}"
    }
}
