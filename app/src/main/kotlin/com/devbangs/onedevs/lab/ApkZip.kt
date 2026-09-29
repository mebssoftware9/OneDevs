package com.devbangs.onedevs.lab

import java.io.RandomAccessFile

/**
 * Random access to a file's bytes, or to an array standing in for one.
 *
 * The parsers below read a few bytes at known offsets and never the whole
 * file, so they take this rather than a stream. Tests pass an array; the
 * analyzer passes the copy it made of the APK.
 */
internal abstract class Bytes {
    abstract val size: Long

    /** Up to [length] bytes from [at]. Fewer at the end of the file, never more. */
    abstract fun read(at: Long, length: Int): ByteArray

    companion object {
        fun of(array: ByteArray): Bytes = object : Bytes() {
            override val size: Long = array.size.toLong()
            override fun read(at: Long, length: Int): ByteArray {
                if (at < 0 || at >= size) return ByteArray(0)
                val end = minOf(size, at + length).toInt()
                return array.copyOfRange(at.toInt(), end)
            }
        }

        fun of(file: RandomAccessFile): Bytes = object : Bytes() {
            override val size: Long = file.length()
            override fun read(at: Long, length: Int): ByteArray {
                if (at < 0 || at >= size) return ByteArray(0)
                val n = minOf(size - at, length.toLong()).toInt()
                val out = ByteArray(n)
                file.seek(at)
                file.readFully(out)
                return out
            }
        }
    }
}

/** One entry as the ZIP's central directory describes it. */
data class ZipItem(
    val name: String,
    /** 0 is stored, 8 is deflated. Nothing else appears in an APK. */
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
) {
    val stored: Boolean get() = method == 0
}

/**
 * The parts of an APK that java.util.zip does not expose.
 *
 * ZipFile answers "what is in here", which is most of the job. It does not
 * say where an entry's bytes start, and that is exactly what 16 KB page
 * alignment is measured on. Nor does it know about the APK Signing Block,
 * which sits between the last entry and the central directory where no ZIP
 * reader looks -- and which is the only place v2 and v3 signatures live.
 *
 * ZIP64 is not handled. An APK never needs it below 4 GB, and the analyzer
 * refuses anything over 600 MB before this is reached.
 */
internal object ApkZip {

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CD_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val CD_HEADER = 46
    private const val LOCAL_HEADER = 30

    /** The APK Signing Block's footer magic: "APK Sig Block 42". */
    private val SIGNING_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** The directory and where it starts, which is where the signing block ends. */
    data class Directory(val items: List<ZipItem>, val offset: Long)

    fun directory(src: Bytes): Directory? {
        val eocd = findEocd(src) ?: return null
        val count = u16(eocd, 10)
        val cdSize = u32(eocd, 12)
        val cdOffset = u32(eocd, 16)
        if (cdOffset == 0xFFFFFFFFL || cdOffset + cdSize > src.size) return null
        val cd = src.read(cdOffset, cdSize.toInt())
        val items = ArrayList<ZipItem>(count)
        var at = 0
        while (at + CD_HEADER <= cd.size && items.size < count) {
            if (u32(cd, at).toInt() != CD_SIGNATURE) break
            val nameLength = u16(cd, at + 28)
            val extraLength = u16(cd, at + 30)
            val commentLength = u16(cd, at + 32)
            if (at + CD_HEADER + nameLength > cd.size) break
            items += ZipItem(
                name = String(cd, at + CD_HEADER, nameLength, Charsets.UTF_8),
                method = u16(cd, at + 10),
                compressedSize = u32(cd, at + 20),
                size = u32(cd, at + 24),
                localHeaderOffset = u32(cd, at + 42),
            )
            at += CD_HEADER + nameLength + extraLength + commentLength
        }
        return Directory(items, cdOffset)
    }

    /**
     * Where an entry's data begins in the file.
     *
     * The central directory's extra field and the local header's are allowed
     * to differ -- zipalign pads the local one -- so the local header is the
     * only honest answer.
     */
    fun dataOffset(src: Bytes, item: ZipItem): Long? {
        val header = src.read(item.localHeaderOffset, LOCAL_HEADER)
        if (header.size < LOCAL_HEADER || u32(header, 0).toInt() != LOCAL_SIGNATURE) return null
        return item.localHeaderOffset + LOCAL_HEADER + u16(header, 26) + u16(header, 28)
    }

    /**
     * The IDs of every block inside the APK Signing Block, in file order.
     *
     * Empty when there is no signing block, which means v1 at most. Each ID is
     * a scheme or a piece of metadata; [SigningBlock] names the ones that
     * matter.
     */
    fun signingBlockIds(src: Bytes, directoryOffset: Long): List<Int> {
        if (directoryOffset < 24 + 8) return emptyList()
        val footer = src.read(directoryOffset - 24, 24)
        if (footer.size < 24) return emptyList()
        if (!SIGNING_MAGIC.indices.all { footer[8 + it] == SIGNING_MAGIC[it] }) return emptyList()
        val blockSize = u64(footer, 0)
        val start = directoryOffset - blockSize - 8
        if (blockSize < 24 || start < 0 || blockSize > MAX_SIGNING_BLOCK) return emptyList()
        val block = src.read(start, (blockSize + 8).toInt())
        if (block.size.toLong() != blockSize + 8 || u64(block, 0) != blockSize) return emptyList()
        val ids = mutableListOf<Int>()
        var at = 8
        val end = block.size - 24
        while (at + 12 <= end) {
            val length = u64(block, at)
            if (length < 4 || at + 8 + length > end) break
            ids += u32(block, at + 8).toInt()
            at += (8 + length).toInt()
        }
        return ids
    }

    /** Anything larger is not a signing block anyone produced on purpose. */
    private const val MAX_SIGNING_BLOCK = 16L * 1024 * 1024

    private fun findEocd(src: Bytes): ByteArray? {
        if (src.size < EOCD_MIN) return null
        val window = minOf(src.size, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = src.read(src.size - window, window)
        for (at in tail.size - EOCD_MIN downTo 0) {
            if (u32(tail, at).toInt() == EOCD_SIGNATURE &&
                at + EOCD_MIN + u16(tail, at + 20) == tail.size
            ) {
                return tail.copyOfRange(at, tail.size)
            }
        }
        return null
    }
}

/** What an APK's signing says, assembled from the ZIP and the signing block. */
data class SigningSchemes(
    val v1: Boolean = false,
    val v2: Boolean = false,
    val v3: Boolean = false,
    val v31: Boolean = false,
    /** Added by Play when it serves the APK, so a file with it came from the store. */
    val playDelivered: Boolean = false,
) {
    val any: Boolean get() = v1 || v2 || v3 || v31
    val modern: Boolean get() = v2 || v3 || v31
}

internal object SigningBlock {
    const val V2 = 0x7109871a
    const val V3 = 0xf05368c0.toInt()
    const val V31 = 0x1b93ad61
    private const val FROSTING = 0x2146444e

    /**
     * v1 is the JAR signature: a .SF file and a signature block file under
     * META-INF. Both have to be there; either alone is a leftover.
     */
    fun schemes(ids: List<Int>, entryNames: List<String>): SigningSchemes {
        val meta = entryNames.filter { it.startsWith("META-INF/") && it.count { c -> c == '/' } == 1 }
        val v1 = meta.any { it.endsWith(".SF") } &&
            meta.any { it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") }
        return SigningSchemes(
            v1 = v1,
            v2 = V2 in ids,
            v3 = V3 in ids,
            v31 = V31 in ids,
            playDelivered = FROSTING in ids,
        )
    }
}

internal fun u16(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

internal fun u32(b: ByteArray, at: Int): Long =
    (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

internal fun u64(b: ByteArray, at: Int): Long =
    u32(b, at) or (u32(b, at + 4) shl 32)
