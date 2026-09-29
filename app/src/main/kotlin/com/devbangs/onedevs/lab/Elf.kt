package com.devbangs.onedevs.lab

/**
 * The one thing about a native library that decides whether it loads on a
 * 16 KB page device: how its loadable segments are aligned.
 *
 * Android 15 introduced devices whose memory pages are 16 KB rather than 4 KB.
 * A library linked for 4 KB pages cannot be mapped on them, and Play refuses
 * updates targeting Android 15 or later that ship one. The alignment is
 * written in the ELF program headers, a few hundred bytes into the file, so
 * this needs the start of the library and nothing else.
 */
internal object Elf {

    private const val PT_LOAD = 1L

    /** Enough for the header and every program header in any real library. */
    const val HEAD_BYTES = 4096

    /** The page size Android 15's large-page devices use. */
    const val PAGE_16K = 16_384L

    data class Info(
        val is64: Boolean,
        /**
         * The smallest p_align of any PT_LOAD segment, or null when the
         * program headers did not fit in the bytes read.
         */
        val loadAlign: Long?,
    ) {
        val supports16k: Boolean? get() = loadAlign?.let { it >= PAGE_16K }
    }

    fun read(head: ByteArray): Info? {
        if (head.size < 52) return null
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return null
        }
        // Android is little-endian on every ABI it has shipped.
        if (head[5].toInt() != 1) return null
        val is64 = when (head[4].toInt()) {
            1 -> false
            2 -> true
            else -> return null
        }
        if (is64 && head.size < 64) return null
        val phoff = if (is64) u64(head, 32) else u32(head, 28)
        val phentsize = u16(head, if (is64) 54 else 42)
        val phnum = u16(head, if (is64) 56 else 44)
        val minimum = if (is64) 56 else 32
        if (phentsize < minimum || phnum == 0) return Info(is64, null)
        if (phoff < 0 || phoff + phentsize.toLong() * phnum > head.size) return Info(is64, null)
        var smallest: Long? = null
        for (i in 0 until phnum) {
            val at = (phoff + i.toLong() * phentsize).toInt()
            if (u32(head, at) != PT_LOAD) continue
            val align = if (is64) u64(head, at + 48) else u32(head, at + 28)
            smallest = if (smallest == null) align else minOf(smallest, align)
        }
        return Info(is64, smallest)
    }
}

/** One .so inside the APK. */
data class NativeLib(
    val path: String,
    val abi: String,
    val bytes: Long,
    val compressedBytes: Long,
    val stored: Boolean,
    /** Null when the ELF could not be read. */
    val elf16k: Boolean?,
    /**
     * For stored libraries, whether the data starts on a 16 KB boundary in
     * the file. Compressed ones are extracted at install, so it does not apply
     * and this is null.
     */
    val zip16k: Boolean?,
) {
    val name: String get() = path.substringAfterLast('/')
    val is64: Boolean get() = abi in ABI_64

    companion object {
        val ABI_64 = setOf("arm64-v8a", "x86_64", "riscv64")
        val ABI_32 = setOf("armeabi-v7a", "armeabi", "x86", "mips")

        /** The 64-bit ABI that a device running a given 32-bit ABI would want. */
        val PAIR = mapOf("armeabi-v7a" to "arm64-v8a", "armeabi" to "arm64-v8a", "x86" to "x86_64")
    }
}
