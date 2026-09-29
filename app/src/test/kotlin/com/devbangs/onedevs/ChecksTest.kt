package com.devbangs.onedevs

import com.devbangs.onedevs.lab.ApkReport
import com.devbangs.onedevs.lab.Certificate
import com.devbangs.onedevs.lab.Check
import com.devbangs.onedevs.lab.Checks
import com.devbangs.onedevs.lab.Component
import com.devbangs.onedevs.lab.ComponentKind
import com.devbangs.onedevs.lab.Components
import com.devbangs.onedevs.lab.CompilerMarker
import com.devbangs.onedevs.lab.DeviceProfile
import com.devbangs.onedevs.lab.DexCounts
import com.devbangs.onedevs.lab.Feature
import com.devbangs.onedevs.lab.ManifestFacts
import com.devbangs.onedevs.lab.Msg
import com.devbangs.onedevs.lab.NativeLib
import com.devbangs.onedevs.lab.PermissionKind
import com.devbangs.onedevs.lab.Platform
import com.devbangs.onedevs.lab.SigningSchemes
import com.devbangs.onedevs.lab.Status
import com.devbangs.onedevs.lab.Verdict
import com.devbangs.onedevs.lab.verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each Lab tool decides, from reports built by hand. As with the
 * findings, these assert which sentence was chosen and how it was judged --
 * never the English -- so they hold in every language and survive rewording.
 */
class ChecksTest {

    private val release = CompilerMarker("R8", "release", 26, "8.9.1", "full")
    private val cert = Certificate(
        subject = "CN=Release, O=X", issuer = "CN=Release, O=X", serial = "1",
        notBefore = 0, notAfter = Checks.PLAY_KEY_VALID_UNTIL + 1, algorithm = "SHA256withRSA",
        sha256 = "AA", sha1 = "BB",
    )

    private fun lib(abi: String, elf16k: Boolean? = true, zip16k: Boolean? = null) = NativeLib(
        path = "lib/$abi/libx.so", abi = abi, bytes = 1000, compressedBytes = 1000,
        stored = zip16k != null, elf16k = if (abi in NativeLib.ABI_64) elf16k else null, zip16k = zip16k,
    )

    private fun report(
        targetSdk: Int = 36,
        minSdk: Int = 26,
        debuggable: Boolean = false,
        testOnly: Boolean = false,
        signature: String? = "AA",
        schemes: SigningSchemes = SigningSchemes(v2 = true, v3 = true),
        certificates: List<Certificate> = listOf(cert),
        compiler: CompilerMarker? = release,
        natives: List<NativeLib> = emptyList(),
        permissions: List<String> = emptyList(),
        kinds: Map<String, PermissionKind> = emptyMap(),
        components: List<Component> = listOf(Component(ComponentKind.Activity, "com.x.Main", exported = true)),
        manifest: ManifestFacts = ManifestFacts(decoded = true, launchers = setOf("com.x.Main")),
        features: List<Feature> = emptyList(),
        versionCode: Long = 10,
        packageName: String = "com.x",
        backup: Boolean = false,
    ) = ApkReport(
        fileName = "x.apk", fileBytes = 1_000_000, packageName = packageName,
        versionName = "1.0", versionCode = versionCode,
        minSdk = minSdk, targetSdk = targetSdk, compileSdk = 36,
        debuggable = debuggable, allowsBackup = backup, allowsCleartext = false,
        permissions = permissions,
        dangerousPermissions = permissions.filter { kinds[it] == PermissionKind.Runtime },
        components = Components(1, 0, 0, 0, components.filter { it.exported }.map { it.name }),
        abis = natives.map { it.abi }.distinct().sorted(), nativeLibraries = natives.size,
        dex = DexCounts(1, 1000, 1, 1, 1), dexEntries = 1, sizes = emptyList(),
        signatureSha256 = signature, signatureScheme = "v2 or later",
        testOnly = testOnly, schemes = schemes, certificates = certificates,
        compiler = compiler, natives = natives, permissionKinds = kinds,
        componentList = components, manifest = manifest, features = features,
    )

    private val phone = DeviceProfile(
        api = 35, release = "15", model = "Test", abis = listOf("arm64-v8a", "armeabi-v7a"),
        features = setOf("android.hardware.touchscreen"), glEs = 0x00030002, pageSize = 4096,
        densityDpi = 420, widthDp = 411, heightDp = 891, smallestWidthDp = 411, freeBytes = 10_000_000_000,
    )

    private val Msg.id: Int
        get() = when (this) {
            is Msg.Str -> id
            is Msg.Plural -> id
            else -> error("not a sentence: $this")
        }

    private fun List<Check>.find(title: Int): Check? = firstOrNull { it.title.id == title }

    @Test
    fun `a clean release build is ready`() {
        val checks = Checks.release(report())
        assertEquals(Verdict.Ready, checks.verdict())
        assertTrue(checks.none { it.status == Status.Fail })
    }

    @Test
    fun `every debug signal fails the release checklist`() {
        val debug = report(
            debuggable = true,
            testOnly = true,
            compiler = CompilerMarker("D8", "debug", 26, "8.9.1", null),
            certificates = listOf(cert.copy(subject = "C=US, O=Android, CN=Android Debug")),
        )
        val signals = Checks.debugSignals(debug)
        assertEquals(4, signals.count { it.status == Status.Fail })
        assertEquals(Verdict.Blocked, Checks.release(debug).verdict())
        assertEquals(Status.Warn, Checks.release(debug).find(R.string.ck_r8_off)?.status)
    }

    @Test
    fun `v1 alone fails from Android 11 and only warns below it`() {
        val v1 = SigningSchemes(v1 = true)
        assertEquals(Status.Fail, Checks.signing(report(schemes = v1, targetSdk = 30)).find(R.string.ck_v1_only)?.status)
        assertEquals(Status.Warn, Checks.signing(report(schemes = v1, targetSdk = 29)).find(R.string.ck_v1_only)?.status)
    }

    @Test
    fun `an unsigned APK stops at the first question`() {
        val checks = Checks.signing(report(signature = null, certificates = emptyList()))
        assertEquals(R.string.f_unsigned_what, checks.first().title.id)
        assertEquals(Status.Fail, checks.first().status)
    }

    @Test
    fun `a key that expires before Play's date is worth a warning`() {
        val short = report(certificates = listOf(cert.copy(notAfter = Checks.PLAY_KEY_VALID_UNTIL - 1)))
        assertEquals(Status.Warn, Checks.signing(short).find(R.string.ck_cert_expiry)?.status)
        assertNotNull(Checks.signing(report()).find(R.string.ck_cert_valid))
    }

    @Test
    fun `32-bit without 64-bit is refused`() {
        assertEquals(listOf("armeabi-v7a"), Checks.missing64(report(natives = listOf(lib("armeabi-v7a")))))
        assertTrue(Checks.missing64(report(natives = listOf(lib("armeabi-v7a"), lib("arm64-v8a")))).isEmpty())
        // x86_64 is not a stand-in for arm64.
        assertEquals(listOf("armeabi-v7a"), Checks.missing64(report(natives = listOf(lib("armeabi-v7a"), lib("x86_64")))))
    }

    @Test
    fun `16 KB alignment fails from target 35 and warns below`() {
        val bad = listOf(lib("arm64-v8a", elf16k = false))
        assertEquals(Status.Fail, Checks.sixteenK(report(natives = bad, targetSdk = 35)).single().status)
        assertEquals(Status.Warn, Checks.sixteenK(report(natives = bad, targetSdk = 34)).single().status)
        assertEquals(Status.Pass, Checks.sixteenK(report(natives = listOf(lib("arm64-v8a")))).single().status)
        // Stored libraries also have to sit on a boundary in the ZIP.
        val stored = Checks.sixteenK(report(natives = listOf(lib("arm64-v8a", zip16k = false))))
        assertEquals(Status.Fail, stored.single { it.title.id == R.string.ck_16k_zip_bad }.status)
        // 32-bit libraries never load on a 16 KB device, so they are not judged.
        assertTrue(Checks.sixteenK(report(natives = listOf(lib("armeabi-v7a")))).isEmpty())
    }

    @Test
    fun `this phone refuses what it cannot run`() {
        assertEquals(Verdict.Ready, Checks.device(report(natives = listOf(lib("arm64-v8a"))), phone).verdict())
        assertEquals(Verdict.Blocked, Checks.device(report(minSdk = 36), phone).verdict())
        assertEquals(Verdict.Blocked, Checks.device(report(natives = listOf(lib("x86_64"))), phone).verdict())
        val camera = report(features = listOf(Feature("android.hardware.camera.ar", required = true)))
        assertEquals(Status.Fail, Checks.device(camera, phone).find(R.string.ck_feature_missing)?.status)
        val optional = report(features = listOf(Feature("android.hardware.camera.ar", required = false)))
        assertEquals(Verdict.Ready, Checks.device(optional, phone).verdict())
    }

    @Test
    fun `a 16 KB phone refuses unaligned libraries for its own ABI`() {
        val big = phone.copy(pageSize = 16_384)
        val r = report(natives = listOf(lib("arm64-v8a", elf16k = false)))
        assertEquals(Status.Fail, Checks.device(r, big).find(R.string.ck_16k_device)?.status)
        assertEquals(null, Checks.device(r, phone).find(R.string.ck_16k_device))
    }

    @Test
    fun `an update is judged on package, version and signer`() {
        val installed = report(versionCode = 10)
        assertEquals(Verdict.Ready, Checks.update(installed, report(versionCode = 11)).verdict())
        assertEquals(Verdict.Blocked, Checks.update(installed, report(versionCode = 9)).verdict())
        assertEquals(Verdict.Blocked, Checks.update(installed, report(versionCode = 11, signature = "CC")).verdict())
        val other = Checks.update(installed, report(packageName = "com.y"))
        assertEquals(R.string.ck_update_package, other.single().title.id)
        val raised = Checks.update(installed, report(versionCode = 11, minSdk = 28))
        assertEquals(Status.Warn, raised.find(R.string.ck_update_min_raised)?.status)
    }

    @Test
    fun `a permission capped by maxSdkVersion is not requested on a newer phone`() {
        val p = "android.permission.WRITE_EXTERNAL_STORAGE"
        val r = report(
            permissions = listOf(p),
            kinds = mapOf(p to PermissionKind.Runtime),
            manifest = ManifestFacts(decoded = true, permissionMaxSdk = mapOf(p to 28)),
        )
        val outcome = Checks.permissionsOnDevice(r, phone).single()
        assertEquals(Status.Pass, outcome.status)
        assertEquals(R.string.po_not_requested, outcome.what.id)
    }

    @Test
    fun `notifications on an old phone need no permission`() {
        val p = "android.permission.POST_NOTIFICATIONS"
        val r = report(permissions = listOf(p), kinds = mapOf(p to PermissionKind.Runtime))
        assertEquals(R.string.po_notif_old, Checks.permissionsOnDevice(r, phone.copy(api = 32)).single().what.id)
        assertEquals(R.string.po_dialog, Checks.permissionsOnDevice(r, phone).single().what.id)
    }

    @Test
    fun `a foreground service type without its permission is caught before a device finds it`() {
        val r = report(
            targetSdk = 34,
            permissions = listOf("android.permission.FOREGROUND_SERVICE"),
            manifest = ManifestFacts(decoded = true, serviceTypes = mapOf("com.x.Player" to 0x2)),
        )
        val missing = Checks.prelaunch(r).find(R.string.ck_fgs_missing)
        assertEquals(Status.Fail, missing?.status)
        assertEquals(listOf("FOREGROUND_SERVICE_MEDIA_PLAYBACK"), missing!!.evidence.map { (it as Msg.Raw).text })
        val fixed = r.copy(permissions = r.permissions + "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        assertEquals(Status.Pass, Checks.prelaunch(fixed).find(R.string.ck_fgs_ok)?.status)
    }

    @Test
    fun `an exported component guarded by nothing is worth a look, the launcher is not`() {
        val open = report(
            components = listOf(
                Component(ComponentKind.Activity, "com.x.Main", exported = true),
                Component(ComponentKind.Receiver, "com.x.Hook", exported = true),
                Component(ComponentKind.Service, "com.x.Guarded", exported = true, permission = "com.x.P"),
            ),
        )
        assertEquals(listOf("com.x.Hook"), Checks.openComponents(open).map { it.name })
        assertEquals(Status.Pass, Checks.components(report()).single().status)
    }

    @Test
    fun `backup with no rules is flagged, with rules is not`() {
        assertEquals(Status.Warn, Checks.backup(report(backup = true)).single().status)
        val ruled = report(backup = true, manifest = ManifestFacts(decoded = true, dataExtractionRules = true))
        assertEquals(Status.Pass, Checks.backup(ruled).single().status)
    }

    @Test
    fun `the version table covers every level the checks can name`() {
        (21..Platform.LATEST).forEach { assertNotNull("no name for API $it", Platform.version(it)) }
        assertTrue(Platform.changes.keys.all { it <= Platform.LATEST })
        assertTrue(Platform.changes.values.flatten().all { it != 0 })
        // Raising 34 to 36 opts into 35 and 36, not 34 again.
        assertEquals(listOf(35, 36), Platform.changesBetween(34, 36).map { it.first })
    }

    @Test
    fun `no check writes English`() {
        // Every title and detail names a resource. A raw string in either
        // would be the Lab speaking one language again.
        val r = report(debuggable = true, natives = listOf(lib("armeabi-v7a", elf16k = false)))
        val all = Checks.release(r) + Checks.device(r, phone) + Checks.screens(r, phone) +
            Checks.prelaunch(r) + Checks.privacy(r) + Checks.proguard(r) + Checks.releaseConfig(r) +
            Checks.version(r) + Checks.targetSdk(r) + Checks.size(r) + Checks.natives(r)
        all.forEach { check ->
            assertFalse("raw title: ${check.title}", check.title is Msg.Raw)
            assertFalse("raw detail: ${check.detail}", check.detail is Msg.Raw)
        }
    }
}
