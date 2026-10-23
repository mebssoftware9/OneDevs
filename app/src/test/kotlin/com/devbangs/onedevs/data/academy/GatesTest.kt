package com.devbangs.onedevs.data.academy

import com.devbangs.onedevs.R
import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Certificate
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.CompilerMarker
import com.devbangs.onedevs.lab.Components
import com.devbangs.onedevs.lab.DexCounts
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.NativeLib
import com.devbangs.onedevs.lab.SigningSchemes
import com.devbangs.onedevs.lab.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gate is a credential's evidence, so it may not pass on a warning or on
 * something the Lab could not see. Built from reports by hand, like the Lab's
 * own checks.
 */
class GatesTest {

    private val releaseCert = Certificate(
        subject = "CN=Release, O=X", issuer = "CN=Release, O=X", serial = "1",
        notBefore = 0, notAfter = Checks.PLAY_KEY_VALID_UNTIL + 1, algorithm = "SHA256withRSA",
        sha256 = "AA", sha1 = "BB",
    )
    private val debugCert = releaseCert.copy(subject = "C=US, O=Android, CN=Android Debug")

    private fun lib(abi: String, elf16k: Boolean? = true) = NativeLib(
        path = "lib/$abi/libx.so", abi = abi, bytes = 1000, compressedBytes = 1000,
        stored = false, elf16k = if (abi in NativeLib.ABI_64) elf16k else null, zip16k = null,
    )

    private fun report(
        targetSdk: Int = ApkReport.PLAY_TARGET_SDK_FLOOR,
        debuggable: Boolean = false,
        certificates: List<Certificate> = listOf(releaseCert),
        schemes: SigningSchemes = SigningSchemes(v2 = true, v3 = true),
        compiler: CompilerMarker? = CompilerMarker("R8", "release", 26, "8.9.1", "full"),
        natives: List<NativeLib> = emptyList(),
    ) = ApkReport(
        fileName = "x.apk", fileBytes = 1_000_000, packageName = "com.x", versionName = "1.0", versionCode = 10,
        minSdk = 26, targetSdk = targetSdk, compileSdk = targetSdk, debuggable = debuggable,
        allowsBackup = false, allowsCleartext = false, permissions = emptyList(), dangerousPermissions = emptyList(),
        components = Components(1, 0, 0, 0, emptyList()), abis = natives.map { it.abi }.distinct(),
        nativeLibraries = natives.size, dex = DexCounts(1, 1000, 1, 1, 1), dexEntries = 1, sizes = emptyList(),
        signatureSha256 = "AA", signatureScheme = "v2 or later", schemes = schemes, certificates = certificates,
        compiler = compiler, natives = natives,
    )

    private val Msg.id: Int get() = (this as Msg.Str).id

    @Test
    fun `a clean release build passes every gate`() {
        ArtifactGate.entries.forEach { gate ->
            assertTrue("$gate", Gates.evaluate(gate, report()).passed)
        }
    }

    @Test
    fun `target SDK below Play's floor fails`() {
        val result = Gates.evaluate(ArtifactGate.TargetSdk, report(targetSdk = ApkReport.PLAY_TARGET_SDK_FLOOR - 1))
        assertFalse(result.passed)
        assertTrue(result.checks.any { it.status == Status.Fail })
    }

    @Test
    fun `the debug key fails signing`() {
        assertFalse(Gates.evaluate(ArtifactGate.Signing, report(certificates = listOf(debugCert))).passed)
    }

    @Test
    fun `a signature scheme the Lab could not read is not a pass, and says why`() {
        val result = Gates.evaluate(ArtifactGate.Signing, report(schemes = SigningSchemes()))
        assertFalse(result.passed)
        assertEquals(R.string.acad_gate_unproven_signing, result.checks.last { it.status == Status.Fail }.title.id)
    }

    @Test
    fun `a release build R8 did not run on is a warning in the Lab and a failure here`() {
        val result = Gates.evaluate(ArtifactGate.ReleaseBuild, report(compiler = null))
        assertFalse(result.passed)
        assertTrue(result.checks.any { it.status == Status.Warn })
        assertEquals(R.string.acad_gate_unproven_release, result.checks.last().title.id)
        assertFalse(Gates.evaluate(ArtifactGate.ReleaseBuild, report(debuggable = true)).passed)
    }

    @Test
    fun `no native code passes, and says so`() {
        val result = Gates.evaluate(ArtifactGate.NativeCode, report())
        assertTrue(result.passed)
        assertEquals(R.string.acad_gate_no_native, result.checks.single().title.id)
    }

    @Test
    fun `32-bit without 64-bit, or a library not aligned for 16 KB, fails`() {
        assertFalse(Gates.evaluate(ArtifactGate.NativeCode, report(natives = listOf(lib("armeabi-v7a")))).passed)
        assertFalse(Gates.evaluate(ArtifactGate.NativeCode, report(natives = listOf(lib("arm64-v8a", elf16k = false)))).passed)
        assertTrue(Gates.evaluate(ArtifactGate.NativeCode, report(natives = listOf(lib("arm64-v8a"), lib("armeabi-v7a")))).passed)
    }
}
