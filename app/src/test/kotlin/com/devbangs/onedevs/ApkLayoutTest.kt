package com.devbangs.onedevs

import com.devbangs.onedevs.lab.ApkZip
import com.devbangs.onedevs.lab.Bytes
import com.devbangs.onedevs.lab.Certificates
import com.devbangs.onedevs.lab.Elf
import com.devbangs.onedevs.lab.SigningBlock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of an APK that no ZIP library reads: where each entry's bytes
 * start, the signing block between the entries and the directory, and the
 * program headers of a native library. Each is built here byte by byte and
 * read back, because the formats are fixed and the tests should not need a
 * real APK to exist.
 */
class ApkLayoutTest {

    private fun zip(vararg entries: Pair<String, ByteArray>, storedName: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, data) ->
                val e = ZipEntry(name)
                if (name == storedName) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    /**
     * Splices an APK Signing Block holding [ids] in front of the central
     * directory, and moves the directory offset in the end record to match --
     * which is what apksigner does.
     */
    private fun withSigningBlock(zip: ByteArray, ids: List<Int>): ByteArray {
        val src = Bytes.of(zip)
        val cd = ApkZip.directory(src)!!.offset.toInt()
        val pairs = ids.map { id -> le(8 + 4 + 4) { putLong(8); putInt(id); putInt(0) } }
        val pairBytes = pairs.fold(ByteArray(0)) { a, b -> a + b }
        val size = pairBytes.size + 8 + 16
        val block = le(8) { putLong(size.toLong()) } + pairBytes +
            le(8) { putLong(size.toLong()) } + "APK Sig Block 42".toByteArray()
        val result = zip.copyOfRange(0, cd) + block + zip.copyOfRange(cd, zip.size)
        // The end-of-central-directory record is the last 22 bytes here: no comment.
        val eocd = result.size - 22
        val moved = cd + block.size
        ByteBuffer.wrap(result, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(moved)
        return result
    }

    @Test
    fun `the central directory lists every entry with its method and sizes`() {
        val bytes = zip(
            "AndroidManifest.xml" to ByteArray(300) { 7 },
            "lib/arm64-v8a/libx.so" to ByteArray(1000) { it.toByte() },
            storedName = "lib/arm64-v8a/libx.so",
        )
        val dir = ApkZip.directory(Bytes.of(bytes))!!
        assertEquals(listOf("AndroidManifest.xml", "lib/arm64-v8a/libx.so"), dir.items.map { it.name })
        val lib = dir.items[1]
        assertTrue(lib.stored)
        assertEquals(1000L, lib.size)
        assertEquals(1000L, lib.compressedSize)
        assertFalse(dir.items[0].stored)
        assertEquals(300L, dir.items[0].size)
    }

    @Test
    fun `the data offset points at the entry's own bytes`() {
        val payload = ByteArray(64) { (it * 3).toByte() }
        val bytes = zip("a.txt" to ByteArray(10), "lib/x86_64/liby.so" to payload, storedName = "lib/x86_64/liby.so")
        val src = Bytes.of(bytes)
        val item = ApkZip.directory(src)!!.items.single { it.name.endsWith(".so") }
        val offset = ApkZip.dataOffset(src, item)!!.toInt()
        assertEquals(payload.toList(), bytes.copyOfRange(offset, offset + payload.size).toList())
    }

    @Test
    fun `no signing block means no v2 or v3`() {
        val bytes = zip("classes.dex" to ByteArray(10))
        val src = Bytes.of(bytes)
        assertTrue(ApkZip.signingBlockIds(src, ApkZip.directory(src)!!.offset).isEmpty())
    }

    @Test
    fun `the signing block is found between the entries and the directory`() {
        val bytes = withSigningBlock(zip("classes.dex" to ByteArray(10)), listOf(SigningBlock.V2, SigningBlock.V3))
        val src = Bytes.of(bytes)
        val dir = ApkZip.directory(src)!!
        // Moving the directory must not lose it.
        assertEquals(listOf("classes.dex"), dir.items.map { it.name })
        val ids = ApkZip.signingBlockIds(src, dir.offset)
        assertEquals(listOf(SigningBlock.V2, SigningBlock.V3), ids)
        val schemes = SigningBlock.schemes(ids, dir.items.map { it.name })
        assertTrue(schemes.v2 && schemes.v3 && schemes.modern)
        assertFalse(schemes.v1)
    }

    @Test
    fun `v1 needs both the signature file and the signature block file`() {
        val names = listOf("META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA")
        assertTrue(SigningBlock.schemes(emptyList(), names).v1)
        assertFalse(SigningBlock.schemes(emptyList(), names.dropLast(1)).v1)
        // Nested files are a library's resources, not a signature.
        assertFalse(SigningBlock.schemes(emptyList(), listOf("META-INF/a/B.SF", "META-INF/a/B.RSA")).v1)
    }

    @Test
    fun `garbage is not a zip`() {
        assertNull(ApkZip.directory(Bytes.of(ByteArray(100) { 1 })))
        assertNull(ApkZip.directory(Bytes.of(ByteArray(3))))
    }

    /** A 64-bit ELF header and [aligns].size PT_LOAD program headers. */
    private fun elf64(vararg aligns: Long): ByteArray {
        val phoff = 64
        val b = ByteBuffer.allocate(phoff + 56 * aligns.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        b.putLong(32, phoff.toLong())
        b.putShort(54, 56)
        b.putShort(56, aligns.size.toShort())
        aligns.forEachIndexed { i, align ->
            val at = phoff + i * 56
            b.putInt(at, 1)
            b.putLong(at + 48, align)
        }
        return b.array()
    }

    @Test
    fun `a library aligned to 16 KB passes and one aligned to 4 KB does not`() {
        assertEquals(true, Elf.read(elf64(16_384, 16_384))!!.supports16k)
        assertEquals(false, Elf.read(elf64(16_384, 4_096))!!.supports16k)
        assertEquals(true, Elf.read(elf64(65_536))!!.supports16k)
        assertTrue(Elf.read(elf64(4_096))!!.is64)
    }

    @Test
    fun `program headers beyond what was read are unknown, not failing`() {
        val full = elf64(16_384, 16_384)
        val info = Elf.read(full.copyOf(80))!!
        assertNull(info.supports16k)
    }

    @Test
    fun `something that is not ELF is not read as one`() {
        assertNull(Elf.read(ByteArray(64)))
        assertNull(Elf.read(ByteArray(10)))
    }

    /** NewPipe's public release certificate, as its APK carries it. */
    private val newPipe = Base64.getMimeDecoder().decode(
        """
        MIIC/TCCAeWgAwIBAgIELJEHRDANBgkqhkiG9w0BAQsFADAvMQswCQYDVQQGEwJE
        RTEgMB4GA1UEAxMXQ2hyaXN0aWFuIFNjaGFiZXNiZXJnZXIwHhcNMTcwNDEzMjA1
        NzQ3WhcNNDIwNDA3MjA1NzQ3WjAvMQswCQYDVQQGEwJERTEgMB4GA1UEAxMXQ2hy
        aXN0aWFuIFNjaGFiZXNiZXJnZXIwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
        AoIBAQDgzp0xEcjDqarA9XD7/QHw0It6OORtJCLM40/uniSg2xht7vtGZyR6sCOi
        y+g6OjnmRVpRBZ6nTNDG0qnEVNi0MadD7HZkIKwyiCKl/p+qVQ9xi0+r6vfwatLd
        lOjRnGMuJSAlR5Mf3MXRfSUC2g+VjWzHxuvjWKax2JDume0WZY39Oy1yeNI6wEtJ
        S9ZofDNiqZL0YLfpCl1zyr3glubdH5xLH+3Ku6bYwcZB+zqUbxrf9l7GpvK/ACdt
        F6de+Fw0RBfqGFGjhNH6IT19gMWLeClllVzStQiMcS/i0X0srv6gEUOhSMDV3bhv
        RZddoa/aJqlyzPRsjNPEk9B0pG7ZAgMBAAGjITAfMB0GA1UdDgQWBBRIfMfdMrNU
        4UxmPO4jFjLNMy3eoTANBgkqhkiG9w0BAQsFAAOCAQEAfLSwdfiV7PgbpLo8W+Vy
        PO2vb9i7QX7E+qosZJ6I3iPNftS76DurJbMHXe+YAQ0bz978uvZJjBz/cmXrDKAn
        KBRsf7uj3f5cDKqipjeoWeMwSR1PvHzVjKvmbvcDzWyEFBrjS2EIyr0K+67oWUXF
        N0wMFHfse1X2XMpQR/QcCaAiyvEyikn9pR2ukiOHTi6p0fPja3GorGVmcDLBkpeX
        icirjnO3GTpkgP2TE3W6HJcYGVOFNeCMgkV7tp7/L9KwxI83KmGSalm1eOcFVnxQ
        Y3MCZ7d5d2MZ0IvfPw3uStZ9VC7yItKwARjOsGLRqD/lM/IpMWC/zKtAL2ZXc0hA
        uQ==
        """.trimIndent(),
    )

    @Test
    fun `a real certificate reads back with the fingerprint keytool prints`() {
        val cert = Certificates.of(newPipe)
        assertNotNull(cert)
        cert!!
        assertTrue(cert.subject.contains("CN=Christian Schabesberger"))
        assertEquals(
            "CB:84:06:9B:D6:81:16:BA:FA:E5:EE:4E:E5:B0:8A:56:7A:A6:D8:98:40:4E:7C:B1:2F:9E:75:6D:F5:CF:5C:AB",
            cert.sha256,
        )
        assertEquals("SHA256withRSA", cert.algorithm)
        assertFalse(cert.debug)
        assertTrue(cert.notAfter > cert.notBefore)
    }

    @Test
    fun `bytes that are not a certificate are not one`() {
        assertNull(Certificates.of(ByteArray(40) { 3 }))
    }
}
