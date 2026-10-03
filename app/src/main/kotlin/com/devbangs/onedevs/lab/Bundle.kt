package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R

/**
 * Just enough protobuf to read an app bundle's manifest.
 *
 * An APK's manifest is Android's binary XML; a bundle's is aapt2's protobuf
 * form, XmlNode in Resources.proto, so that bundletool can rewrite it for
 * each split. Only four wire types exist and only a handful of fields are
 * needed, so this reads them directly rather than shipping a protobuf runtime.
 */
internal object Proto {

    /** One field: its number, and either a number or the bounds of its bytes. */
    class Field(val number: Int, val value: Long, val from: Int, val to: Int)

    /** The fields between [from] and [to], or null if the bytes are not protobuf. */
    fun fields(b: ByteArray, from: Int = 0, to: Int = b.size): List<Field>? {
        val out = ArrayList<Field>()
        var at = from
        while (at < to) {
            val (key, afterKey) = varint(b, at, to) ?: return null
            at = afterKey
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> {
                    val (v, next) = varint(b, at, to) ?: return null
                    out += Field(number, v, at, next)
                    at = next
                }
                1 -> {
                    if (at + 8 > to) return null
                    out += Field(number, 0, at, at + 8)
                    at += 8
                }
                2 -> {
                    val (length, start) = varint(b, at, to) ?: return null
                    if (length < 0 || start + length > to) return null
                    out += Field(number, length, start, (start + length).toInt())
                    at = (start + length).toInt()
                }
                5 -> {
                    if (at + 4 > to) return null
                    out += Field(number, 0, at, at + 4)
                    at += 4
                }
                else -> return null
            }
        }
        return out
    }

    private fun varint(b: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var at = from
        while (at < to && shift < 64) {
            val byte = b[at++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to at
            shift += 7
        }
        return null
    }

    fun string(b: ByteArray, f: Field): String = String(b, f.from, f.to - f.from, Charsets.UTF_8)
}

/** A manifest element as the bundle stores it: name, attributes, children. */
data class ProtoElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<ProtoElement>,
) {
    fun all(tag: String): List<ProtoElement> = children.filter { it.name == tag }
    fun first(tag: String): ProtoElement? = children.firstOrNull { it.name == tag }
}

internal object ProtoXml {

    private const val MAX_DEPTH = 64

    /** The root element of an XmlNode, or null when it is not one. */
    fun parse(b: ByteArray): ProtoElement? = node(b, 0, b.size, 0)

    private fun node(b: ByteArray, from: Int, to: Int, depth: Int): ProtoElement? {
        if (depth > MAX_DEPTH) return null
        val element = Proto.fields(b, from, to)?.firstOrNull { it.number == 1 } ?: return null
        return element(b, element.from, element.to, depth)
    }

    private fun element(b: ByteArray, from: Int, to: Int, depth: Int): ProtoElement? {
        val fields = Proto.fields(b, from, to) ?: return null
        var name = ""
        val attributes = LinkedHashMap<String, String>()
        val children = ArrayList<ProtoElement>()
        fields.forEach { f ->
            when (f.number) {
                3 -> name = Proto.string(b, f)
                4 -> attribute(b, f)?.let { (k, v) -> attributes[k] = v }
                5 -> node(b, f.from, f.to, depth + 1)?.let { children += it }
            }
        }
        return ProtoElement(name, attributes, children)
    }

    /**
     * An attribute's name and value. aapt2 keeps the value as written; where
     * it did not, the compiled primitive is read instead.
     */
    private fun attribute(b: ByteArray, f: Proto.Field): Pair<String, String>? {
        val fields = Proto.fields(b, f.from, f.to) ?: return null
        val name = fields.firstOrNull { it.number == 2 }?.let { Proto.string(b, it) } ?: return null
        val written = fields.firstOrNull { it.number == 3 }?.let { Proto.string(b, it) }
        val value = written?.takeIf { it.isNotEmpty() }
            ?: fields.firstOrNull { it.number == 6 }?.let { primitive(b, it) }
            ?: ""
        return name to value
    }

    /** Item.prim: int_decimal (6), int_hexadecimal (7) or boolean (8). */
    private fun primitive(b: ByteArray, item: Proto.Field): String? {
        val prim = Proto.fields(b, item.from, item.to)?.firstOrNull { it.number == 7 } ?: return null
        val value = Proto.fields(b, prim.from, prim.to)?.firstOrNull { it.number in 6..8 } ?: return null
        return when (value.number) {
            8 -> (value.value != 0L).toString()
            else -> value.value.toInt().toString()
        }
    }
}

/** One module of a bundle: base, a feature, or an asset pack. */
data class BundleModule(
    val name: String,
    val files: Int,
    val bytes: Long,
    val compressedBytes: Long,
    val dexBytes: Long,
    val nativeBytes: Long,
    val resourceBytes: Long,
    val assetBytes: Long,
    val hasManifest: Boolean,
    val hasResourceTable: Boolean,
    /** "feature", "asset-pack" or null for base, from the manifest's dist:module. */
    val type: String?,
)

/** What one .aab says about itself. */
data class BundleReport(
    val fileName: String,
    val fileBytes: Long,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val debuggable: Boolean,
    val permissions: List<String>,
    val modules: List<BundleModule>,
    val abis: List<String>,
    val dex: DexCounts,
    val compiler: CompilerMarker?,
    val hasBundleConfig: Boolean,
    val signed: Boolean,
    val debugSigned: Boolean,
    val hasMapping: Boolean,
    val hasNativeSymbols: Boolean,
    val hasDependencyInfo: Boolean,
    /** 64-bit libraries whose ELF segments are not aligned for 16 KB pages. */
    val not16k: List<String>,
) {
    val base: BundleModule? get() = modules.firstOrNull { it.name == "base" }
}

/**
 * Reads a bundle from its entries and the bytes of the few it needs, so the
 * rules run on a list in tests and on a ZipFile on the phone.
 */
internal object Bundles {

    /** One entry of the archive. */
    class Entry(val name: String, val bytes: Long, val compressedBytes: Long)

    /** Play's limit on the compressed download of the base module and its config splits. */
    const val BASE_LIMIT = 200L * 1024 * 1024

    /** The largest versionCode Google Play accepts. */
    const val VERSION_CODE_MAX = 2_100_000_000L

    private const val META = "BUNDLE-METADATA/"

    fun read(
        fileName: String,
        fileBytes: Long,
        entries: List<Entry>,
        bytesOf: (String) -> ByteArray?,
        elfHead: (String) -> ByteArray?,
    ): BundleReport {
        val moduleNames = entries.map { it.name.substringBefore('/') }
            .filter { name -> entries.any { it.name == "$name/manifest/AndroidManifest.xml" } }
            .distinct()
        val manifests = moduleNames.associateWith { name ->
            bytesOf("$name/manifest/AndroidManifest.xml")?.let { ProtoXml.parse(it) }
        }
        val modules = moduleNames.map { name ->
            val own = entries.filter { it.name.startsWith("$name/") }
            fun sum(dir: String) = own.filter { it.name.startsWith("$name/$dir/") }.sumOf { it.bytes }
            BundleModule(
                name = name,
                files = own.size,
                bytes = own.sumOf { it.bytes },
                compressedBytes = own.sumOf { it.compressedBytes },
                dexBytes = sum("dex"),
                nativeBytes = sum("lib"),
                resourceBytes = sum("res") + own.filter { it.name == "$name/resources.pb" }.sumOf { it.bytes },
                assetBytes = sum("assets"),
                hasManifest = manifests[name] != null,
                hasResourceTable = own.any { it.name == "$name/resources.pb" },
                type = manifests[name]?.let(::moduleType),
            )
        }.sortedWith(compareBy({ it.name != "base" }, { it.name }))

        val manifest = manifests["base"]
        val sdk = manifest?.first("uses-sdk")
        val application = manifest?.first("application")
        val dexEntries = entries.filter { it.name.startsWith("base/dex/") && it.name.endsWith(".dex") }
        val counts = dexEntries.mapNotNull { e -> bytesOf(e.name)?.let { DexHeader.read(it) } }
        val firstDex = dexEntries.minByOrNull { it.name }?.let { bytesOf(it.name) }
        val signers = entries.filter { e ->
            e.name.startsWith("META-INF/") && e.name.count { it == '/' } == 1 &&
                listOf(".RSA", ".EC", ".DSA").any { e.name.uppercase().endsWith(it) }
        }
        val libs = entries.filter { it.name.contains("/lib/") && it.name.endsWith(".so") }

        return BundleReport(
            fileName = fileName,
            fileBytes = fileBytes,
            packageName = manifest?.attributes?.get("package").orEmpty(),
            versionCode = manifest?.attributes?.get("versionCode")?.toLongOrNull() ?: 0L,
            versionName = manifest?.attributes?.get("versionName").orEmpty(),
            minSdk = sdk?.attributes?.get("minSdkVersion")?.toIntOrNull() ?: 1,
            targetSdk = sdk?.attributes?.get("targetSdkVersion")?.toIntOrNull()
                ?: sdk?.attributes?.get("minSdkVersion")?.toIntOrNull() ?: 0,
            debuggable = application?.attributes?.get("debuggable") == "true",
            permissions = manifest?.all("uses-permission").orEmpty()
                .mapNotNull { it.attributes["name"] }.distinct().sorted(),
            modules = modules,
            abis = libs.map { it.name.substringAfter("/lib/").substringBefore('/') }.distinct().sorted(),
            dex = DexHeader.merge(counts),
            compiler = firstDex?.let { DexCode.marker(it) },
            hasBundleConfig = entries.any { it.name == "BundleConfig.pb" },
            signed = signers.isNotEmpty(),
            debugSigned = signers.any { e -> bytesOf(e.name)?.let { contains(it, "Android Debug") } == true },
            hasMapping = entries.any { it.name.startsWith(META) && it.name.endsWith("proguard.map") },
            hasNativeSymbols = entries.any { it.name.startsWith(META + "com.android.tools.build.debugsymbols/") },
            hasDependencyInfo = entries.any { it.name.startsWith(META + "com.android.tools.build.libraries/") },
            not16k = libs.filter { e -> e.name.substringAfter("/lib/").substringBefore('/') in NativeLib.ABI_64 }
                .filter { e -> elfHead(e.name)?.let { Elf.read(it)?.supports16k } == false }
                .map { it.name.substringAfterLast('/') }
                .distinct()
                .sorted(),
        )
    }

    /** dist:module's dist:type, or "feature" for a module that does not say. */
    private fun moduleType(manifest: ProtoElement): String? {
        val module = manifest.first("module") ?: return null
        return module.attributes["type"] ?: "feature"
    }

    private fun contains(haystack: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // ---- What the tools say ------------------------------------------------

    /** The App Bundle Validation tool: what Play will check on upload. */
    fun validation(b: BundleReport): List<Check> = buildList {
        val base = b.base
        if (base == null || !base.hasManifest) {
            add(Check(Status.Fail, str(R.string.ab_no_base), str(R.string.ab_no_base_d)))
            return@buildList
        }
        add(
            if (b.hasBundleConfig) {
                Check(Status.Pass, str(R.string.ab_config))
            } else {
                Check(Status.Fail, str(R.string.ab_no_config), str(R.string.ab_no_config_d))
            },
        )
        if (!base.hasResourceTable) add(Check(Status.Fail, str(R.string.ab_no_resources), str(R.string.ab_no_resources_d)))
        if (base.dexBytes == 0L) add(Check(Status.Warn, str(R.string.ab_no_dex), str(R.string.ab_no_dex_d)))
        add(
            when {
                !b.signed -> Check(Status.Fail, str(R.string.ab_unsigned), str(R.string.ab_unsigned_d))
                b.debugSigned -> Check(Status.Fail, str(R.string.ab_debug_signed), str(R.string.ab_debug_signed_d))
                else -> Check(Status.Pass, str(R.string.ab_signed), str(R.string.ab_signed_d))
            },
        )
        if (b.debuggable) add(Check(Status.Fail, str(R.string.ck_debuggable), str(R.string.ab_debuggable_d)))
        add(
            if (b.targetSdk >= ApkReport.PLAY_TARGET_SDK_FLOOR) {
                Check(Status.Pass, str(R.string.ab_target_ok, b.targetSdk))
            } else {
                Check(Status.Fail, str(R.string.ab_target_low, b.targetSdk), str(R.string.ab_target_low_d, ApkReport.PLAY_TARGET_SDK_FLOOR))
            },
        )
        when {
            b.versionCode <= 0 -> add(Check(Status.Fail, str(R.string.ab_version_missing)))
            b.versionCode > VERSION_CODE_MAX -> add(Check(Status.Fail, str(R.string.ab_version_high), str(R.string.ab_version_high_d)))
            else -> add(Check(Status.Pass, str(R.string.ab_version, num(b.versionCode), b.versionName)))
        }
        add(
            when {
                base.compressedBytes > BASE_LIMIT -> Check(Status.Fail, str(R.string.ab_base_big), str(R.string.ab_base_big_d))
                base.compressedBytes > BASE_LIMIT * 3 / 4 -> Check(Status.Warn, str(R.string.ab_base_near), str(R.string.ab_base_big_d))
                else -> Check(Status.Pass, str(R.string.ab_base_ok))
            },
        )
        if (b.not16k.isNotEmpty()) {
            add(Check(Status.Fail, str(R.string.ab_16k), str(R.string.ab_16k_d), b.not16k.map { Msg.Raw(it) }))
        } else if (b.abis.any { it in NativeLib.ABI_64 }) {
            add(Check(Status.Pass, str(R.string.ab_16k_ok)))
        }
        when {
            b.compiler?.shrunk == true && !b.hasMapping ->
                add(Check(Status.Warn, str(R.string.ab_no_mapping), str(R.string.ab_no_mapping_d)))
            b.hasMapping -> add(Check(Status.Pass, str(R.string.ab_mapping)))
        }
        if (b.abis.isNotEmpty()) {
            add(
                if (b.hasNativeSymbols) {
                    Check(Status.Pass, str(R.string.ab_symbols))
                } else {
                    Check(Status.Warn, str(R.string.ab_no_symbols), str(R.string.ab_no_symbols_d))
                },
            )
        }
        if (!b.hasDependencyInfo) add(Check(Status.Info, str(R.string.ab_no_deps), str(R.string.ab_no_deps_d)))
    }
}
