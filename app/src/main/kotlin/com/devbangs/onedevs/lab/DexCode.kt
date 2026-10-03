package com.devbangs.onedevs.lab

/**
 * The note D8 and R8 leave in every DEX they write.
 *
 * Both compilers add a string like
 * `~~R8{"backend":"dex","compilation-mode":"release","min-api":26,"version":"8.5.35"}`
 * to the string pool. It is the most direct evidence there is of how a build
 * was made: which tool, which mode, which version. R8 means the code was
 * shrunk; D8 means it was only dexed.
 */
data class CompilerMarker(
    val tool: String,
    val mode: String?,
    val minApi: Int?,
    val version: String?,
    /** "full" or "compatibility", R8 only. */
    val r8Mode: String?,
) {
    val shrunk: Boolean get() = tool == "R8"
    val release: Boolean get() = mode == "release"
}

/** What the classes in the DEX files say, as opposed to what the header counts. */
data class CodeShape(
    val classes: Int = 0,
    /** Classes whose simple name is one or two characters: what R8 renames to. */
    val obfuscated: Int = 0,
    /** Package to class count, largest first, capped. */
    val packages: List<Pair<String, Int>> = emptyList(),
    /** [KnownSdk.id]s whose classes appear. */
    val sdks: Set<String> = emptySet(),
    /** [CodeMarkers] ids whose classes appear. */
    val markers: Set<String> = emptySet(),
    /** [DeprecatedApis] ids the code refers to. */
    val apis: Set<String> = emptySet(),
) {
    /** 0..100, or null with nothing to divide. */
    val obfuscatedPercent: Int? get() = if (classes == 0) null else obfuscated * 100 / classes
}

/** One DEX file, by name. */
data class DexFile(val name: String, val bytes: Long, val counts: DexCounts)

/**
 * Reads the class names and the compiler marker out of a whole DEX.
 *
 * The header gives counts; this follows class_defs through type_ids to the
 * string data to get names, which is what obfuscation and third-party SDKs
 * are visible in. Every offset is checked against the array, because the file
 * came from whoever built the APK.
 */
internal object DexCode {

    private const val PACKAGE_DEPTH = 3
    private const val TOP_PACKAGES = 12

    data class One(
        val marker: CompilerMarker?,
        val classes: Int,
        val obfuscated: Int,
        val packages: Map<String, Int>,
        val sdks: Set<String>,
        val markers: Set<String> = emptySet(),
        val apis: Set<String> = emptySet(),
    )

    fun read(dex: ByteArray): One? {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() ||
            dex[2] != 'x'.code.toByte()
        ) {
            return null
        }
        val stringIdsSize = u32(dex, 0x38)
        val stringIdsOff = u32(dex, 0x3C)
        val typeIdsSize = u32(dex, 0x40)
        val typeIdsOff = u32(dex, 0x44)
        val classDefsSize = u32(dex, 0x60)
        val classDefsOff = u32(dex, 0x64)
        if (!fits(dex, stringIdsOff, stringIdsSize * 4) || !fits(dex, typeIdsOff, typeIdsSize * 4) ||
            !fits(dex, classDefsOff, classDefsSize * 32)
        ) {
            return null
        }
        var obfuscated = 0
        var classes = 0
        val packages = HashMap<String, Int>()
        val sdks = HashSet<String>()
        val markers = HashSet<String>()
        for (i in 0 until classDefsSize.toInt()) {
            val typeIndex = u32(dex, (classDefsOff + i * 32L).toInt())
            if (typeIndex >= typeIdsSize) continue
            val stringIndex = u32(dex, (typeIdsOff + typeIndex * 4).toInt())
            if (stringIndex >= stringIdsSize) continue
            val dataOff = u32(dex, (stringIdsOff + stringIndex * 4).toInt())
            val descriptor = string(dex, dataOff) ?: continue
            classes++
            val path = descriptor.removePrefix("L").removeSuffix(";")
            val simple = path.substringAfterLast('/').substringBefore('$')
            if (simple.length <= 2) obfuscated++
            val pkg = path.substringBeforeLast('/', "").split('/')
                .take(PACKAGE_DEPTH).joinToString(".")
            if (pkg.isNotEmpty()) packages[pkg] = (packages[pkg] ?: 0) + 1
            KnownSdks.all.firstOrNull { sdk -> sdk.prefixes.any { path.startsWith(it) } }
                ?.let { sdks += it.id }
            CodeMarkers.all.forEach { (id, prefixes) -> if (prefixes.any { path.startsWith(it) }) markers += id }
        }
        val apis = DeprecatedApis.find(dex) { string(dex, it) }
        return One(marker(dex), classes, obfuscated, packages, sdks, markers, apis)
    }

    fun merge(all: List<One>): Pair<CompilerMarker?, CodeShape> {
        val packages = HashMap<String, Int>()
        all.forEach { one -> one.packages.forEach { (k, v) -> packages[k] = (packages[k] ?: 0) + v } }
        // R8 over D8 when files disagree: a shrunk build can carry a DEX that
        // was only dexed, but not the other way round.
        val marker = all.mapNotNull { it.marker }.let { found ->
            found.firstOrNull { it.tool == "R8" } ?: found.firstOrNull()
        }
        return marker to CodeShape(
            classes = all.sumOf { it.classes },
            obfuscated = all.sumOf { it.obfuscated },
            packages = packages.entries.sortedByDescending { it.value }.take(TOP_PACKAGES)
                .map { it.key to it.value },
            sdks = all.flatMapTo(HashSet()) { it.sdks },
            markers = all.flatMapTo(HashSet()) { it.markers },
            apis = all.flatMapTo(HashSet()) { it.apis },
        )
    }

    /**
     * The marker, found by its bytes rather than by walking the string pool.
     * It is always ASCII and always starts "~~", which nothing else in a
     * string pool does in practice.
     */
    fun marker(dex: ByteArray): CompilerMarker? {
        val tilde = '~'.code.toByte()
        var at = 0
        while (at < dex.size - 5) {
            if (dex[at] == tilde && dex[at + 1] == tilde && dex[at + 3] == '8'.code.toByte() &&
                dex[at + 4] == '{'.code.toByte()
            ) {
                val tool = dex[at + 2].toInt().toChar()
                if (tool == 'R' || tool == 'D' || tool == 'L') {
                    var end = at
                    while (end < dex.size && dex[end] != 0.toByte()) end++
                    return parseMarker(String(dex, at, end - at, Charsets.UTF_8))
                }
            }
            at++
        }
        return null
    }

    fun parseMarker(text: String): CompilerMarker? {
        if (!text.startsWith("~~") || text.length < 5) return null
        fun field(key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?([^\",}]*)\"?").find(text)?.groupValues?.get(1)
        return CompilerMarker(
            tool = text.substring(2, 4),
            mode = field("compilation-mode"),
            minApi = field("min-api")?.toIntOrNull(),
            version = field("version"),
            r8Mode = field("r8-mode"),
        )
    }

    /** A string_data_item: ULEB128 length in UTF-16 units, then MUTF-8, then 0. */
    private fun string(dex: ByteArray, offset: Long): String? {
        if (offset <= 0 || offset >= dex.size) return null
        var at = offset.toInt()
        // Skip the ULEB128; the terminating zero is what bounds the bytes.
        var guard = 0
        while (at < dex.size && dex[at].toInt() and 0x80 != 0 && guard++ < 5) at++
        at++
        val start = at
        while (at < dex.size && dex[at] != 0.toByte()) at++
        if (at >= dex.size) return null
        return String(dex, start, at - start, Charsets.UTF_8)
    }

    private fun fits(b: ByteArray, offset: Long, length: Long): Boolean =
        length == 0L || (offset > 0 && offset + length <= b.size)
}

// Doors for the tests, as DexHeader has.
internal fun dexCodeForTest(bytes: ByteArray) = DexCode.read(bytes)

internal fun markerForTest(text: String) = DexCode.parseMarker(text)
