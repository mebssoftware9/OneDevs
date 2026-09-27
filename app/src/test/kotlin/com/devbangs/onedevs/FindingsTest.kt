package com.devbangs.onedevs

import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Components
import com.devbangs.onedevs.lab.DexCounts
import com.devbangs.onedevs.lab.Findings
import com.devbangs.onedevs.lab.Severity
import com.devbangs.onedevs.lab.SizeSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tool decides to say, which is the part that makes it a tool rather
 * than a file viewer. Every rule is a pure function of the report, so every
 * rule can be argued with here.
 */
class FindingsTest {

    private fun report(
        minSdk: Int = 26,
        targetSdk: Int = 36,
        debuggable: Boolean = false,
        cleartext: Boolean = false,
        backup: Boolean = false,
        dangerous: List<String> = emptyList(),
        exported: List<String> = listOf("com.x.MainActivity"),
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        methods: Int = 120_710,
        signature: String? = "AA:BB",
    ) = ApkReport(
        fileName = "x.apk", fileBytes = 1_000, packageName = "com.x",
        versionName = "1.0", versionCode = 1,
        minSdk = minSdk, targetSdk = targetSdk, compileSdk = 36,
        debuggable = debuggable, allowsBackup = backup, allowsCleartext = cleartext,
        permissions = dangerous, dangerousPermissions = dangerous,
        components = Components(2, 0, 1, 1, exported),
        abis = abis, nativeLibraries = abis.size,
        dex = DexCounts(10, methods, 1, 1, 1),
        sizes = listOf(SizeSlice("Native libraries", 40_000, 4)),
        signatureSha256 = signature, signatureScheme = "v2 or later",
    )

    @Test
    fun `a clean release build says nothing`() {
        assertTrue(Findings.of(report()).isEmpty())
    }

    @Test
    fun `multidex is not a finding on a modern minSdk`() {
        // 120,710 methods crosses the 65,536 ceiling, and from API 21 the
        // platform loads multiple DEX files natively. Flagging it teaches
        // people to scroll past findings.
        assertFalse(Findings.multidexWorthMentioning(report(minSdk = 26)))
        assertTrue(Findings.multidexWorthMentioning(report(minSdk = 19)))
        assertFalse(Findings.multidexWorthMentioning(report(minSdk = 19, methods = 1_000)))
    }

    @Test
    fun `a debug build is a note about the file, not a verdict on the app`() {
        val finding = Findings.of(report(debuggable = true)).single()
        assertEquals(Severity.Note, finding.severity)
        assertTrue(finding.action.contains("release"))
    }

    @Test
    fun `x86 is flagged only when it is riding along with real ABIs`() {
        val mixed = Findings.of(report(abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")))
        val finding = mixed.single { it.what.contains("x86") }
        assertEquals(Severity.Worth, finding.severity)
        assertTrue(finding.action.contains("abiFilters"))
        // An x86-only build is deliberate -- someone targeting emulators or
        // Chromebooks -- and telling them to remove their only ABI is wrong.
        assertTrue(Findings.of(report(abis = listOf("x86", "x86_64"))).isEmpty())
        assertTrue(Findings.of(report(abis = emptyList())).isEmpty())
    }

    @Test
    fun `a dangerous permission is named, not counted`() {
        val finding = Findings.of(
            report(dangerous = listOf("android.permission.READ_MEDIA_IMAGES")),
        ).single()
        assertTrue(finding.evidence.contains("READ_MEDIA_IMAGES"))
    }

    @Test
    fun `one exported component is the launcher, more is worth asking about`() {
        assertTrue(Findings.of(report(exported = listOf("com.x.MainActivity"))).isEmpty())
        val finding = Findings.of(
            report(exported = listOf("com.x.MainActivity", "com.x.DeepLinkReceiver")),
        ).single()
        assertEquals(Severity.Worth, finding.severity)
        assertTrue(finding.evidence.contains("DeepLinkReceiver"))
    }

    @Test
    fun `blocking findings come first`() {
        val findings = Findings.of(
            report(targetSdk = 30, cleartext = true, backup = true, debuggable = true),
        )
        assertEquals(Severity.Blocking, findings.first().severity)
        assertEquals(
            listOf(Severity.Blocking, Severity.Worth, Severity.Note, Severity.Note),
            findings.map { it.severity },
        )
    }

    @Test
    fun `every finding says what to do about it`() {
        val all = Findings.of(
            report(
                targetSdk = 30, debuggable = true, cleartext = true, backup = true,
                dangerous = listOf("android.permission.CAMERA"),
                exported = listOf("a.B", "a.C"),
                abis = listOf("arm64-v8a", "x86"),
                signature = null,
            ),
        )
        assertTrue(all.size >= 7)
        all.forEach {
            assertTrue("${it.what} has no why", it.why.isNotBlank())
            assertTrue("${it.what} has no action", it.action.isNotBlank())
            assertTrue("${it.what} does not end cleanly", it.what.endsWith("."))
        }
    }
}
