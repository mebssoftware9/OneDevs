package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    private fun report(
        permissions: List<String> = emptyList(),
        libraries: Map<String, String> = emptyMap(),
        markers: Set<String> = emptySet(),
        layouts: LayoutStats? = null,
        night: Int = 0,
        activities: List<Component> = emptyList(),
        sdks: Set<String> = emptySet(),
    ) = ApkReport(
        fileName = "a.apk", fileBytes = 1, packageName = "com.example", versionName = "1", versionCode = 1,
        minSdk = 26, targetSdk = 35, compileSdk = 35, debuggable = false, allowsBackup = false,
        allowsCleartext = false, permissions = permissions, dangerousPermissions = emptyList(),
        components = Components(0, 0, 0, 0, emptyList()), abis = emptyList(), nativeLibraries = 0,
        dex = DexCounts(1, 1, 1, 1, 1), dexEntries = 1, sizes = emptyList(), signatureSha256 = null,
        signatureScheme = "v2", libraries = libraries, codeMarkers = markers, layouts = layouts,
        nightResources = night, componentList = activities, code = CodeShape(sdks = sdks),
    )

    private fun ids(checks: List<Check>) = checks.map { (it.title as Msg.Str).id }

    @Test
    fun `pre-release libraries are warned about`() {
        val checks = Quality.dependencies(report(libraries = mapOf("androidx.core:core" to "1.16.0-alpha02")))
        assertTrue(ids(checks).contains(R.string.q_deps_unstable))
    }

    @Test
    fun `androidx groups by its second segment`() {
        assertEquals("androidx.compose", Quality.family("androidx.compose.ui"))
        assertEquals("com.squareup.okhttp3", Quality.family("com.squareup.okhttp3"))
    }

    @Test
    fun `safetynet blocks integrity readiness`() {
        val checks = Quality.integrity(report(markers = setOf(CodeMarkers.SAFETYNET)))
        assertEquals(Verdict.Blocked, checks.verdict())
    }

    @Test
    fun `night resources pass dark mode`() {
        assertTrue(ids(Quality.darkMode(report(night = 3))).contains(R.string.q_dark_resources))
        assertTrue(ids(Quality.darkMode(report())).contains(R.string.q_dark_none))
    }

    @Test
    fun `unlabelled images and fixed text sizes are reported`() {
        val stats = LayoutStats(files = 2, images = 4, unlabelledImages = 1, textSizesFixed = 2, smallTargets = 0)
        assertTrue(ids(Quality.accessibility(report(layouts = stats))).contains(R.string.q_a11y_images_bad))
        assertTrue(ids(Quality.fontScaling(report(layouts = stats))).contains(R.string.q_font_fixed))
        assertTrue(ids(Quality.accessibility(report())).contains(R.string.q_layouts_none))
    }

    @Test
    fun `activities handling rotation are named`() {
        val main = Component(ComponentKind.Activity, "com.example.Main", exported = true, configChanges = 0x80 or 0x400)
        assertTrue(ids(Quality.orientation(report(activities = listOf(main)))).contains(R.string.q_rotate_handles))
    }

    @Test
    fun `data safety follows permissions and sdks`() {
        val r = report(
            permissions = listOf("android.permission.INTERNET", "android.permission.ACCESS_FINE_LOCATION"),
            sdks = setOf("firebase-analytics"),
        )
        val labels = Quality.dataSafety(r).map { it.label }
        assertTrue(labels.contains(R.string.ds_location))
        assertTrue(labels.contains(R.string.ds_activity))
        assertTrue(ids(Quality.dataSafetyChecks(report())).contains(R.string.ds_offline))
    }
}
