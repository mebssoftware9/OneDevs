package com.devbangs.onedevs

import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Components
import com.devbangs.onedevs.lab.DexCounts
import com.devbangs.onedevs.lab.Finding
import com.devbangs.onedevs.lab.Findings
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.Severity
import com.devbangs.onedevs.lab.SizeSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tool decides to say, which is the part that makes it a tool rather
 * than a file viewer.
 *
 * These assert which sentence was chosen, not what it says. The rules name
 * resources now, so a test matching on English would break every time the
 * wording improved, and would pass while three languages said nothing at all.
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
        dexFiles: Int = 10,
        dexEntries: Int = 10,
        dexBytes: Long = 30_000_000,
        signature: String? = "AA:BB",
    ) = ApkReport(
        fileName = "x.apk", fileBytes = 1_000, packageName = "com.x",
        versionName = "1.0", versionCode = 1,
        minSdk = minSdk, targetSdk = targetSdk, compileSdk = 36,
        debuggable = debuggable, allowsBackup = backup, allowsCleartext = cleartext,
        permissions = dangerous, dangerousPermissions = dangerous,
        components = Components(2, 0, 1, 1, exported),
        abis = abis, nativeLibraries = abis.size,
        dex = DexCounts(dexFiles, methods, 1, 1, 1),
        dexEntries = dexEntries,
        sizes = listOf(
            SizeSlice("Native libraries", 40_000, 4),
            SizeSlice("DEX", dexBytes, dexFiles),
        ),
        signatureSha256 = signature, signatureScheme = "v2 or later",
    )

    /**
     * Named resourceId rather than id so it cannot be confused with the
     * member it reads inside each branch.
     */
    private val Msg.resourceId: Int
        get() = when (this) {
            is Msg.Str -> id
            is Msg.Plural -> id
            is Msg.Raw -> error("a rule wrote text instead of naming a resource: $text")
        }

    private val Finding.raw: List<String>
        get() = evidence.filterIsInstance<Msg.Raw>().map { it.text }

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
        assertEquals(R.string.f_debug_what, finding.what.resourceId)
    }

    @Test
    fun `x86 is flagged only when it is riding along with real ABIs`() {
        val mixed = Findings.of(report(abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")))
        val finding = mixed.single { it.what.resourceId == R.string.f_abi_what }
        assertEquals(Severity.Worth, finding.severity)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), finding.raw)
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
        assertEquals(R.plurals.f_perms_what, finding.what.resourceId)
        assertTrue(finding.raw.contains("READ_MEDIA_IMAGES"))
    }

    @Test
    fun `one exported component is the launcher, more is worth asking about`() {
        assertTrue(Findings.of(report(exported = listOf("com.x.MainActivity"))).isEmpty())
        val finding = Findings.of(
            report(exported = listOf("com.x.MainActivity", "com.x.DeepLinkReceiver")),
        ).single()
        assertEquals(Severity.Worth, finding.severity)
        assertTrue(finding.raw.contains("DeepLinkReceiver"))
    }

    @Test
    fun `entries named dex that are not dex files are a packer`() {
        val finding = Findings.of(report(dexFiles = 1, dexEntries = 12)).single()
        assertEquals(Severity.Worth, finding.severity)
        assertEquals(R.string.f_packed_mixed_what, finding.what.resourceId)
        assertEquals(listOf(11, 12), (finding.what as Msg.Str).args)
    }

    @Test
    fun `nothing readable at all says so differently`() {
        val finding = Findings.of(report(dexFiles = 0, dexEntries = 3)).single()
        assertEquals(R.string.f_packed_none_what, finding.what.resourceId)
    }

    @Test
    fun `one enormous DEX with almost no methods is a packer too`() {
        // MovieBox: 61.1 MB of DEX declaring 106 methods, in a single entry.
        // The entry count matches, so only the ratio catches this shape.
        val movieBox = report(dexFiles = 1, dexEntries = 1, dexBytes = 64_072_581, methods = 106)
        val finding = Findings.of(movieBox).single()
        assertEquals(R.plurals.f_packed_huge_what, finding.what.resourceId)
        // The headline is the two numbers that do not fit together; the ratio
        // is evidence underneath, and "1 readable of 1" is not said at all.
        assertEquals(106, (finding.what as Msg.Plural).args[1])
        assertEquals(listOf(R.string.ev_per_method), finding.evidence.map { it.resourceId })
        assertFalse(Findings.methodCountIsMeaningful(movieBox))
    }

    @Test
    fun `each shape explains itself`() {
        // One conclusion, three reasons. A card reading "the DEX is 590 KB per
        // method" once explained itself with "Android will not load these
        // directly", where "these" referred to nothing: the reason belonged to
        // a different shape. Three distinct resources is what prevents that.
        val many = Findings.of(report(dexFiles = 1, dexEntries = 12)).single().why.resourceId
        val huge = Findings.of(
            report(dexFiles = 1, dexEntries = 1, dexBytes = 64_072_581, methods = 106),
        ).single().why.resourceId
        val none = Findings.of(report(dexFiles = 0, dexEntries = 3)).single().why.resourceId
        assertEquals(3, setOf(many, huge, none).size)
        assertEquals(R.string.f_packed_mixed_why, many)
        assertEquals(R.string.f_packed_huge_why, huge)
        assertEquals(R.string.f_packed_none_why, none)
    }

    @Test
    fun `a normal build is nowhere near the ceiling`() {
        // OneDevs itself: 29.1 MB across 120,636 methods, which is 253 bytes
        // each. The threshold is eighty times that, so ordinary apps have
        // room to be unusual without being accused.
        val ordinary = report(dexFiles = 10, dexEntries = 10, dexBytes = 30_513_561, methods = 120_636)
        assertTrue(Findings.of(ordinary).isEmpty())
        assertTrue(Findings.methodCountIsMeaningful(ordinary))
    }

    @Test
    fun `a tiny app is not flagged for having few methods`() {
        assertTrue(
            Findings.of(report(dexFiles = 1, dexEntries = 1, dexBytes = 400_000, methods = 900)).isEmpty(),
        )
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
    fun `no rule writes its own English`() {
        // The whole point of the change. A rule that builds a sentence itself
        // is a rule three quarters of this app's users cannot read, and the
        // resourceId accessor throws on Msg.Raw for exactly that reason.
        val all = Findings.of(
            report(
                targetSdk = 30, debuggable = true, cleartext = true, backup = true,
                dangerous = listOf("android.permission.CAMERA"),
                exported = listOf("a.B", "a.C"),
                abis = listOf("arm64-v8a", "x86"),
                dexFiles = 1, dexEntries = 4,
                signature = null,
            ),
        )
        assertTrue(all.size >= 8)
        all.forEach {
            assertNotEquals(0, it.what.resourceId)
            assertNotEquals(0, it.why.resourceId)
            assertNotEquals(0, it.action.resourceId)
            // An observation and its reason are never the same sentence.
            assertNotEquals(it.what.resourceId, it.why.resourceId)
        }
    }
}
