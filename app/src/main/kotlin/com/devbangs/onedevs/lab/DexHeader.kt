package com.devbangs.onedevs.lab

/**
 * The counts in a DEX file's header.
 *
 * A DEX header is fixed-layout: eight bytes of magic, then a series of
 * little-endian uint32 pairs at known offsets. Reading the six counts needs
 * seventy bytes and no library, which is why this is a file and not a
 * dependency.
 *
 * Nothing here trusts the file. A truncated or renamed DEX returns null rather
 * than an exception, because the caller is iterating over ZIP entries chosen
 * by whoever built the APK, not by us.
 */
internal object DexHeader {

    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"
    private const val HEADER_BYTES = 0x70

    private const val STRING_IDS = 0x38
    private const val TYPE_IDS = 0x40
    private const val FIELD_IDS = 0x50
    private const val METHOD_IDS = 0x58
    private const val CLASS_DEFS = 0x60

    /** Counts for one DEX, or null if these bytes are not one. */
    fun read(bytes: ByteArray): DexCounts? {
        if (bytes.size < HEADER_BYTES) return null
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
        val counts = intArrayOf(METHOD_IDS, FIELD_IDS, CLASS_DEFS, STRING_IDS, TYPE_IDS)
            .map { u32(bytes, it) }
        // A count read as negative means the field was above 2^31, which no
        // real DEX has: the file is truncated, or it is not a DEX at all.
        if (counts.any { it < 0 }) return null
        return DexCounts(
            files = 1,
            methods = counts[0],
            fields = counts[1],
            classes = counts[2],
            strings = counts[3],
        )
    }

    /**
     * Adds up several DEX files into one set of counts. Multidex is ordinary,
     * and the number a developer cares about is the app's, not classes2's.
     */
    fun merge(all: List<DexCounts>): DexCounts = DexCounts(
        files = all.size,
        methods = all.sumOf { it.methods },
        fields = all.sumOf { it.fields },
        classes = all.sumOf { it.classes },
        strings = all.sumOf { it.strings },
    )

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}

/**
 * Groups an APK's entries into the slices a developer recognises.
 *
 * Kept apart from the ZIP reading so it can be tested without an APK: the
 * interesting part is the grouping rules, not the file handling.
 */
internal fun sliceSizes(entries: List<Pair<String, Long>>): List<SizeSlice> {
    val buckets = LinkedHashMap<String, MutableList<Long>>()
    entries.forEach { (name, size) ->
        val label = when {
            name.endsWith(".dex") -> "DEX"
            name.startsWith("lib/") -> "Native libraries"
            name.startsWith("res/") -> "Resources"
            name == "resources.arsc" -> "Resource table"
            name.startsWith("assets/") -> "Assets"
            name.startsWith("META-INF/") -> "Signing"
            name == "AndroidManifest.xml" -> "Manifest"
            else -> "Other"
        }
        buckets.getOrPut(label) { mutableListOf() }.add(size)
    }
    return buckets.map { (label, sizes) -> SizeSlice(label, sizes.sum(), sizes.size) }
        .sortedByDescending { it.bytes }
}

// Named entry points for tests. The parsers stay internal -- nothing outside
// this package should be reading DEX headers -- but the rules they encode are
// exactly what is worth pinning, so the test source set gets a door rather
// than the whole file getting a wider visibility it does not need.
internal fun dexHeaderForTest(bytes: ByteArray): DexCounts? = DexHeader.read(bytes)

internal fun mergeDexForTest(all: List<DexCounts>): DexCounts = DexHeader.merge(all)

internal fun sliceSizesForTest(entries: List<Pair<String, Long>>): List<SizeSlice> =
    sliceSizes(entries)
