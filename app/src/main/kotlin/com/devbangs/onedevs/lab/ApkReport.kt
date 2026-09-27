package com.devbangs.onedevs.lab

/** What one DEX file contains, read from its fixed-layout header. */
data class DexCounts(
    val files: Int,
    val methods: Int,
    val fields: Int,
    val classes: Int,
    val strings: Int,
) {
    /**
     * A single DEX addresses methods with a 16-bit index, so 65,536 is the
     * ceiling that forced multidex on a generation of apps. Still worth
     * saying: a build that has just crossed it has changed shape.
     */
    val overSingleDexLimit: Boolean get() = methods > 65_536
}

/** One line of the size breakdown. */
data class SizeSlice(val label: String, val bytes: Long, val entries: Int)

/** A component declared in the manifest. */
data class Components(
    val activities: Int,
    val services: Int,
    val receivers: Int,
    val providers: Int,
    val exported: List<String>,
)

/** Everything one APK says about itself. */
data class ApkReport(
    val fileName: String,
    val fileBytes: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val debuggable: Boolean,
    val allowsBackup: Boolean,
    val allowsCleartext: Boolean,
    val permissions: List<String>,
    val dangerousPermissions: List<String>,
    val components: Components,
    val abis: List<String>,
    val nativeLibraries: Int,
    val dex: DexCounts,
    /**
     * Entries named *.dex, whether or not they parse as one.
     *
     * Separate from [DexCounts.files], which counts the ones whose header
     * actually says DEX. When the two disagree, something is being carried
     * under a .dex name that is not code Android will load directly.
     */
    val dexEntries: Int,
    val sizes: List<SizeSlice>,
    val signatureSha256: String?,
    val signatureScheme: String,
) {
    companion object {
        /**
         * Play's floor for new apps and updates. A constant here because it is
         * Google's number and it moves every August, and the one place it is
         * written should be the place someone thinks to check.
         */
        const val PLAY_TARGET_SDK_FLOOR = 35
    }

    val meetsPlayTargetFloor: Boolean get() = targetSdk >= PLAY_TARGET_SDK_FLOOR
}
