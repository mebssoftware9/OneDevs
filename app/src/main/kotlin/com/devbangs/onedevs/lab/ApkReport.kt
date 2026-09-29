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
    // Everything below arrived with the Lab's other tools. Defaults are what
    // an absent value means, so a report built by hand -- in a test, say --
    // describes an ordinary release build rather than a broken one.
    val testOnly: Boolean = false,
    val largeHeap: Boolean = false,
    /** False means libraries stay uncompressed in the APK and load from it directly. */
    val extractNativeLibs: Boolean = true,
    val appClass: String? = null,
    val screens: ScreenSupport = ScreenSupport(),
    /** uses-feature entries that name a feature; the GL ES one is [glEsVersion]. */
    val features: List<Feature> = emptyList(),
    /** Required OpenGL ES version, packed as the platform packs it: 0x00030002 is 3.2. */
    val glEsVersion: Int = 0,
    val componentList: List<Component> = emptyList(),
    val permissionKinds: Map<String, PermissionKind> = emptyMap(),
    val schemes: SigningSchemes = SigningSchemes(),
    val certificates: List<Certificate> = emptyList(),
    val natives: List<NativeLib> = emptyList(),
    val items: List<ZipItem> = emptyList(),
    val dexFiles: List<DexFile> = emptyList(),
    val compiler: CompilerMarker? = null,
    val code: CodeShape = CodeShape(),
    val manifest: ManifestFacts = ManifestFacts(),
    /** The manifest as text, or null when it could not be decoded. */
    val manifestXml: String? = null,
    /** META-INF/com/android/build/gradle/app-metadata.properties, when AGP wrote one. */
    val buildMetadata: Map<String, String> = emptyMap(),
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

    val debugCertificate: Boolean get() = certificates.any { it.debug }

    fun requests(permission: String): Boolean = permission in permissions
}

/** supports-screens, as ApplicationInfo's flags carry it. */
data class ScreenSupport(
    val small: Boolean = true,
    val normal: Boolean = true,
    val large: Boolean = true,
    val xlarge: Boolean = true,
    val anyDensity: Boolean = true,
)

data class Feature(val name: String, val required: Boolean)

enum class ComponentKind { Activity, Service, Receiver, Provider }

/** A component with what decides who can reach it. */
data class Component(
    val kind: ComponentKind,
    val name: String,
    val exported: Boolean,
    val permission: String? = null,
    /** Providers only. */
    val authority: String? = null,
    /** Activities only: portrait or landscape, fixed. */
    val orientationLocked: Boolean = false,
)

/**
 * How a permission is granted, which is what a tester will actually meet.
 *
 * [Runtime] is a dialog. [Special] is a page in Settings the user has to be
 * sent to. [Install] is granted without asking. [Signature] is never granted
 * to an app from Play. [Unknown] is a permission this device does not define:
 * another app's custom one, or a platform one from a newer Android.
 */
enum class PermissionKind { Runtime, Special, Install, Signature, Unknown }

/** One signing certificate, as X.509 describes it. */
data class Certificate(
    val subject: String,
    val issuer: String,
    val serial: String,
    val notBefore: Long,
    val notAfter: Long,
    val algorithm: String,
    val sha256: String,
    val sha1: String,
) {
    /**
     * The Android SDK's debug keystore always has this subject. Every
     * developer machine has a different key under it, and all of them are
     * unsafe for release.
     */
    val debug: Boolean get() = subject.contains("CN=Android Debug")
}
