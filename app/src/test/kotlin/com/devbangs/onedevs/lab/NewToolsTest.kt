package com.devbangs.onedevs.lab

import com.devbangs.onedevs.R
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tools that read references out of the DEX, an app bundle's protobuf
 * manifest, and the listing against the code. Each input is built byte by
 * byte here, so the tests say exactly which structure the parser is held to.
 */
class NewToolsTest {

    // ---- A DEX with just the tables the scan reads -------------------------

    private fun uleb(out: ByteArrayOutputStream, value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v != 0) byte = byte or 0x80
            out.write(byte)
        } while (v != 0)
    }

    private fun ByteArray.put32(at: Int, value: Int) {
        for (i in 0..3) this[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun ByteArray.put16(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
    }

    /** strings, types (as string indices), protos (shorty index), methods (class type, proto, name). */
    private fun dex(strings: List<String>, types: List<Int>, protos: List<Int>, methods: List<Triple<Int, Int, Int>>): ByteArray {
        val stringIds = 0x70
        val typeIds = stringIds + strings.size * 4
        val protoIds = typeIds + types.size * 4
        val methodIds = protoIds + protos.size * 12
        val data = methodIds + methods.size * 8
        val pool = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val at = data + pool.size()
            uleb(pool, s.length)
            pool.write(s.toByteArray(Charsets.UTF_8))
            pool.write(0)
            at
        }
        val b = ByteArray(data + pool.size())
        "dex\n035\u0000".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b.put32(0x38, strings.size); b.put32(0x3C, stringIds)
        b.put32(0x40, types.size); b.put32(0x44, typeIds)
        b.put32(0x48, protos.size); b.put32(0x4C, protoIds)
        b.put32(0x58, methods.size); b.put32(0x5C, methodIds)
        offsets.forEachIndexed { i, off -> b.put32(stringIds + i * 4, off) }
        types.forEachIndexed { i, s -> b.put32(typeIds + i * 4, s) }
        protos.forEachIndexed { i, shorty -> b.put32(protoIds + i * 12, shorty) }
        methods.forEachIndexed { i, (cls, proto, name) ->
            b.put16(methodIds + i * 8, cls)
            b.put16(methodIds + i * 8 + 2, proto)
            b.put32(methodIds + i * 8 + 4, name)
        }
        pool.toByteArray().copyInto(b, data)
        return b
    }

    @Test
    fun `finds deprecated classes, methods and the right overload`() {
        val strings = listOf(
            "Landroid/os/AsyncTask;", // 0
            "Landroid/os/Environment;", // 1
            "getExternalStorageDirectory", // 2
            "Landroid/os/Vibrator;", // 3
            "vibrate", // 4
            "VJ", // 5
            "VL", // 6
            "Landroid/webkit/WebView;", // 7
            "Landroid/support/v4/app/Fragment;", // 8
            "Landroid/text/Html;", // 9
            "fromHtml", // 10
            "LLI", // 11
        )
        val types = listOf(0, 1, 3, 7, 8, 9)
        val protos = listOf(5, 6, 11)
        val methods = listOf(
            Triple(1, 1, 2), // Environment.getExternalStorageDirectory
            Triple(2, 0, 4), // Vibrator.vibrate(long)
            Triple(5, 2, 10), // Html.fromHtml(String, int): the current overload
        )
        val found = dexCodeForTest(dex(strings, types, protos, methods))!!.apis
        assertEquals(
            setOf("asynctask", "external-dir", "vibrate-ms", "support-library", DeprecatedApis.WEBVIEW),
            found,
        )
    }

    @Test
    fun `a removed API is blocking once the app targets past it`() {
        val report = report(apis = setOf("app-cache", "asynctask"), target = 35)
        val checks = DeprecatedApis.checks(report)
        assertEquals(Status.Fail, checks.first { it.title == Msg.Raw("android.webkit.WebSettings.setAppCacheEnabled") }.status)
        assertEquals(Status.Warn, checks.first { it.title == Msg.Raw("android.os.AsyncTask") }.status)
    }

    @Test
    fun `an old billing library fails and a current one passes`() {
        val old = DeprecatedApis.checks(report(libraries = mapOf("com.android.billingclient:billing" to "6.2.1")))
        assertTrue(old.any { it.status == Status.Fail && it.title == str(R.string.dep_billing_old, "6.2.1") })
        val current = DeprecatedApis.checks(report(libraries = mapOf("com.android.billingclient:billing" to "8.0.0")))
        assertTrue(current.none { it.status == Status.Fail })
    }

    // ---- A bundle's protobuf manifest ---------------------------------------

    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) = apply {
            key(field, 0)
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun bytes(field: Int, value: ByteArray) = apply {
            key(field, 2)
            varintRaw(value.size.toLong())
            out.write(value)
        }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
        fun message(field: Int, body: Pb) = bytes(field, body.out.toByteArray())
        private fun key(field: Int, wire: Int) = varintRaw((field shl 3 or wire).toLong())
        private fun varintRaw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }

    private fun attr(name: String, value: String) = Pb().string(1, "http://schemas.android.com/apk/res/android")
        .string(2, name).string(3, value)

    private fun element(name: String, vararg attrs: Pb, children: List<Pb> = emptyList()): Pb {
        val e = Pb().string(3, name)
        attrs.forEach { e.message(4, it) }
        children.forEach { e.message(5, Pb().message(1, it)) }
        return e
    }

    private fun manifest(): ByteArray {
        // versionCode written only as a compiled primitive, the way aapt2 can.
        val versionCode = Pb().string(2, "versionCode")
            .message(6, Pb().message(7, Pb().varint(6, 42)))
        val root = element(
            "manifest",
            Pb().string(2, "package").string(3, "com.example.bundle"),
            versionCode,
            attr("versionName", "1.4"),
            children = listOf(
                element("uses-sdk", attr("minSdkVersion", "24"), attr("targetSdkVersion", "34")),
                element("uses-permission", attr("name", "android.permission.INTERNET")),
                element("application", attr("debuggable", "true")),
            ),
        )
        return Pb().message(1, root).out.toByteArray()
    }

    @Test
    fun `reads a bundle's manifest and judges it`() {
        val files = mapOf(
            "base/manifest/AndroidManifest.xml" to manifest(),
            "META-INF/UPLOAD.RSA" to "....CN=Android Debug....".toByteArray(),
        )
        val entries = listOf(
            "base/manifest/AndroidManifest.xml", "base/resources.pb", "base/dex/classes.dex",
            "BundleConfig.pb", "META-INF/UPLOAD.RSA", "base/lib/arm64-v8a/libgame.so",
        ).map { Bundles.Entry(it, 1000, 500) }
        val b = Bundles.read("app.aab", 6000, entries, { files[it] }, { null })

        assertEquals("com.example.bundle", b.packageName)
        assertEquals(42L, b.versionCode)
        assertEquals("1.4", b.versionName)
        assertEquals(24, b.minSdk)
        assertEquals(34, b.targetSdk)
        assertTrue(b.debuggable)
        assertEquals(listOf("android.permission.INTERNET"), b.permissions)
        assertEquals(listOf("arm64-v8a"), b.abis)
        assertTrue(b.signed && b.debugSigned)
        assertEquals("base", b.modules.single().name)

        val failed = Bundles.validation(b).filter { it.status == Status.Fail }.map { it.title }
        assertTrue(str(R.string.ab_debug_signed) in failed)
        assertTrue(str(R.string.ck_debuggable) in failed)
        assertTrue(str(R.string.ab_target_low, 34) in failed)
        assertFalse(str(R.string.ab_no_config) in failed)
    }

    @Test
    fun `garbage is not a manifest`() {
        assertEquals(null, ProtoXml.parse(byteArrayOf(0x7F, 0x7F, 0x7F)))
    }

    // ---- Listing and policy ----------------------------------------------------

    @Test
    fun `a no-ads claim over an ads SDK is blocking`() {
        val r = report(sdks = setOf("gma"), label = "Morpho")
        val checks = Store.consistency(r, Listing(title = "Morpho: Files", full = "Convert files. No ads, ever."))
        assertTrue(checks.any { it.status == Status.Fail && it.title == str(R.string.lc_ads) })
        assertTrue(checks.any { it.status == Status.Pass && it.title == str(R.string.lc_name_ok, "Morpho") })
    }

    @Test
    fun `a policy page is read for what the app does`() {
        val r = report(sdks = setOf("gma"), label = "Morpho", permissions = listOf("android.permission.CAMERA"))
        val html = "<html><script>var x=1;</script><body><h1>Morpho privacy</h1><p>" +
            "We keep files on your device. ".repeat(30) + "Write to help@example.com to have data deleted.</p></body></html>"
        val text = Store.text(html)
        assertFalse(text.contains("var x"))
        val checks = Store.privacyPolicy(r, Store.Page("https://example.com/p", 200, "text/html", text))
        assertTrue(checks.any { it.title == str(R.string.pp_names) })
        assertTrue(checks.any { it.title == str(R.string.pp_contact) })
        val gaps = checks.first { it.title == str(R.string.pp_gaps) }
        assertEquals(listOf(Msg.Raw("Ads"), Msg.Raw("Camera")), gaps.evidence)
    }

    @Test
    fun `an unreachable or insecure policy stops early`() {
        val checks = Store.privacyPolicy(null, Store.Page("http://example.com", 404, "text/html", ""))
        assertEquals(listOf(Status.Fail, Status.Fail), checks.map { it.status })
    }

    @Test
    fun `app content asks for reviewer access when the code signs in`() {
        val checks = Store.appContent(report(apis = setOf(DeprecatedApis.SIGN_IN_CREDENTIALS)))
        assertTrue(checks.any { it.title == str(R.string.ac_access) })
        assertTrue(checks.any { it.title == str(R.string.ac_deletion) })
    }

    private fun report(
        apis: Set<String> = emptySet(),
        sdks: Set<String> = emptySet(),
        target: Int = 35,
        libraries: Map<String, String> = emptyMap(),
        label: String = "",
        permissions: List<String> = listOf("android.permission.INTERNET"),
    ) = ApkReport(
        fileName = "a.apk", fileBytes = 1, packageName = "com.example", versionName = "1", versionCode = 1,
        minSdk = 24, targetSdk = target, compileSdk = 35, debuggable = false, allowsBackup = false,
        allowsCleartext = false, permissions = permissions, dangerousPermissions = emptyList(),
        components = Components(0, 0, 0, 0, emptyList()), abis = emptyList(), nativeLibraries = 0,
        dex = DexCounts(1, 1, 1, 1, 1), dexEntries = 1, sizes = emptyList(), signatureSha256 = null,
        signatureScheme = "v2", code = CodeShape(sdks = sdks, apis = apis), libraries = libraries, label = label,
        dexFiles = listOf(DexFile("classes.dex", 1, DexCounts(1, 1, 1, 1, 1))),
    )
}
