package com.devbangs.onedevs

import com.devbangs.onedevs.lab.BackupRules
import com.devbangs.onedevs.lab.BinaryXml
import com.devbangs.onedevs.lab.DexCode
import com.devbangs.onedevs.lab.ManifestFacts
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two compiled formats the Lab reads past their headers: DEX, for class
 * names and the compiler's own note, and Android's binary XML, for the
 * manifest. Both are written here by hand -- small, but laid out exactly as
 * the real tools lay them out -- and read back.
 */
class CodeAndManifestTest {

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** A DEX whose only content is one class per descriptor, plus [extra] strings. */
    private fun dex(classes: List<String>, extra: List<String> = emptyList()): ByteArray {
        val strings = classes + extra
        val stringIds = 0x70
        val typeIds = stringIds + strings.size * 4
        val classDefs = typeIds + classes.size * 4
        var data = classDefs + classes.size * 32
        val b = le(data + strings.sumOf { it.length + 3 })
        b.put(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
        b.putInt(0x38, strings.size); b.putInt(0x3C, stringIds)
        b.putInt(0x40, classes.size); b.putInt(0x44, typeIds)
        b.putInt(0x60, classes.size); b.putInt(0x64, classDefs)
        strings.forEachIndexed { i, s ->
            b.putInt(stringIds + i * 4, data)
            b.put(data, s.length.toByte())
            s.toByteArray().forEachIndexed { j, c -> b.put(data + 1 + j, c) }
            data += s.length + 2
        }
        classes.indices.forEach { i ->
            b.putInt(typeIds + i * 4, i)
            b.putInt(classDefs + i * 32, i)
        }
        return b.array()
    }

    private val r8 = "~~R8{\"backend\":\"dex\",\"compilation-mode\":\"release\",\"has-checksums\":false," +
        "\"min-api\":24,\"r8-mode\":\"full\",\"version\":\"8.9.1\"}"

    @Test
    fun `class names give obfuscation, packages and known SDKs`() {
        val one = DexCode.read(
            dex(
                listOf(
                    "Lcom/example/app/Main;",
                    "La/b;",
                    "Lb0/a;",
                    "Lcom/google/firebase/analytics/FirebaseAnalytics;",
                ),
                extra = listOf(r8),
            ),
        )
        assertNotNull(one)
        one!!
        assertEquals(4, one.classes)
        assertEquals(2, one.obfuscated)
        assertEquals(1, one.packages["com.example.app"])
        assertTrue("firebase-analytics" in one.sdks)
        val marker = one.marker!!
        assertEquals("R8", marker.tool)
        assertEquals("release", marker.mode)
        assertEquals(24, marker.minApi)
        assertEquals("8.9.1", marker.version)
        assertEquals("full", marker.r8Mode)
        assertTrue(marker.shrunk && marker.release)
    }

    @Test
    fun `D8 in debug mode says so`() {
        val marker = DexCode.parseMarker("~~D8{\"backend\":\"dex\",\"compilation-mode\":\"debug\",\"min-api\":26,\"version\":\"8.2.2\"}")!!
        assertEquals("D8", marker.tool)
        assertFalse(marker.shrunk)
        assertFalse(marker.release)
        assertNull(marker.r8Mode)
    }

    @Test
    fun `merging prefers R8 and adds the counts up`() {
        val a = DexCode.read(dex(listOf("La/a;"), extra = listOf("~~D8{\"compilation-mode\":\"release\"}")))!!
        val b = DexCode.read(dex(listOf("Lcom/x/Player;"), extra = listOf(r8)))!!
        val (marker, shape) = DexCode.merge(listOf(a, b))
        assertEquals("R8", marker!!.tool)
        assertEquals(2, shape.classes)
        assertEquals(50, shape.obfuscatedPercent)
    }

    @Test
    fun `a file that is not DEX is not read as one`() {
        assertNull(DexCode.read(ByteArray(0x70)))
        assertNull(DexCode.read(ByteArray(8)))
    }

    // ---- Binary XML ------------------------------------------------------

    private class A(val name: String, val type: Int, val data: Int = 0, val text: String? = null)
    private class E(val name: String, val attrs: List<A> = emptyList(), val children: List<E> = emptyList())

    private fun str(name: String, value: String) = A(name, BinaryXml.TYPE_STRING, text = value)
    private fun bool(name: String, value: Boolean) = A(name, BinaryXml.TYPE_BOOLEAN, if (value) -1 else 0)
    private fun int(name: String, value: Int) = A(name, BinaryXml.TYPE_INT_DEC, value)
    private fun hex(name: String, value: Int) = A(name, BinaryXml.TYPE_INT_HEX, value)
    private fun ref(name: String, id: Int) = A(name, BinaryXml.TYPE_REFERENCE, id)

    /** Writes [root] the way aapt2 does: a UTF-16 string pool, a namespace, the elements. */
    private fun axml(root: E): ByteArray {
        val pool = mutableListOf<String>()
        fun idx(s: String): Int = pool.indexOf(s).takeIf { it >= 0 } ?: pool.add(s).let { pool.size - 1 }
        idx("android"); idx(BinaryXml.ANDROID)
        fun collect(e: E) {
            idx(e.name); e.attrs.forEach { idx(it.name); it.text?.let(::idx) }; e.children.forEach(::collect)
        }
        collect(root)

        val body = ByteArrayOutputStream()
        fun chunk(bytes: ByteArray) = body.write(bytes)
        fun namespace(type: Int) = chunk(
            le(24).putShort(type.toShort()).putShort(16).putInt(24).putInt(1).putInt(-1)
                .putInt(idx("android")).putInt(idx(BinaryXml.ANDROID)).array(),
        )
        fun element(e: E) {
            val size = 36 + 20 * e.attrs.size
            val b = le(size).putShort(0x0102).putShort(16).putInt(size).putInt(1).putInt(-1)
                .putInt(-1).putInt(idx(e.name)).putShort(20).putShort(20)
                .putShort(e.attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
            e.attrs.forEach { a ->
                b.putInt(idx(BinaryXml.ANDROID)).putInt(idx(a.name))
                    .putInt(a.text?.let(::idx) ?: -1)
                    .putShort(8).put(0).put(a.type.toByte())
                    .putInt(a.text?.let(::idx) ?: a.data)
            }
            chunk(b.array())
            e.children.forEach(::element)
            chunk(le(24).putShort(0x0103).putShort(16).putInt(24).putInt(1).putInt(-1).putInt(-1).putInt(idx(e.name)).array())
        }
        namespace(0x0100)
        element(root)
        namespace(0x0101)

        val strings = ByteArrayOutputStream()
        val offsets = pool.map { s ->
            val at = strings.size()
            strings.write(le(2).putShort(s.length.toShort()).array())
            strings.write(s.toByteArray(Charsets.UTF_16LE))
            strings.write(byteArrayOf(0, 0))
            at
        }
        while (strings.size() % 4 != 0) strings.write(0)
        val poolHeader = 28 + 4 * pool.size
        val poolSize = poolHeader + strings.size()
        val poolChunk = le(poolHeader).putShort(0x0001).putShort(28).putInt(poolSize).putInt(pool.size)
            .putInt(0).putInt(0).putInt(poolHeader).putInt(0)
            .apply { offsets.forEach { putInt(it) } }.array() + strings.toByteArray()

        val total = 8 + poolChunk.size + body.size()
        return le(8).putShort(0x0003).putShort(8).putInt(total).array() + poolChunk + body.toByteArray()
    }

    private val manifest = E(
        "manifest",
        listOf(str("package", "com.x")),
        listOf(
            E("uses-permission", listOf(str("name", "android.permission.READ_EXTERNAL_STORAGE"), int("maxSdkVersion", 32))),
            E("queries"),
            E(
                "application",
                listOf(
                    ref("networkSecurityConfig", 0x7f150001),
                    bool("fullBackupContent", false),
                    bool("resizeableActivity", false),
                ),
                listOf(
                    E(
                        "activity",
                        listOf(str("name", ".Main")),
                        listOf(
                            E(
                                "intent-filter",
                                children = listOf(
                                    E("action", listOf(str("name", "android.intent.action.MAIN"))),
                                    E("category", listOf(str("name", "android.intent.category.LAUNCHER"))),
                                ),
                            ),
                        ),
                    ),
                    E("service", listOf(str("name", "com.x.Player"), hex("foregroundServiceType", 0x2))),
                ),
            ),
        ),
    )

    @Test
    fun `a compiled manifest parses into its elements`() {
        val root = BinaryXml.parse(axml(manifest))
        assertNotNull(root)
        root!!
        assertEquals("manifest", root.name)
        assertEquals("com.x", root.attr("package")?.text)
        val app = root.child("application")!!
        assertEquals(false, app.attr("fullBackupContent")?.bool)
        assertTrue(app.attr("networkSecurityConfig")!!.isReference)
        assertEquals(2, app.children.size)
    }

    @Test
    fun `manifest facts come out of the attributes PackageManager does not expose`() {
        val facts = ManifestFacts.of(BinaryXml.parse(axml(manifest)), "com.x")
        assertTrue(facts.decoded)
        assertTrue(facts.networkSecurityConfig)
        assertFalse(facts.dataExtractionRules)
        assertEquals(BackupRules.Disabled, facts.fullBackupContent)
        assertEquals(false, facts.resizeable)
        assertTrue(facts.queries)
        assertEquals(mapOf("android.permission.READ_EXTERNAL_STORAGE" to 32), facts.permissionMaxSdk)
        // ".Main" is relative to the package, as the manifest allows.
        assertEquals(setOf("com.x.Main"), facts.launchers)
        assertEquals(mapOf("com.x.Player" to 0x2), facts.serviceTypes)
    }

    @Test
    fun `rendering gives back readable XML with references named when possible`() {
        val bytes = axml(manifest)
        val root = BinaryXml.parse(bytes)!!
        val text = BinaryXml.render(root, BinaryXml.prefixes(bytes)) { id ->
            if (id == 0x7f150001) "@xml/network_security_config" else null
        }
        assertTrue(text.startsWith("<manifest"))
        assertTrue(text.contains("xmlns:android=\"${BinaryXml.ANDROID}\""))
        assertTrue(text.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(text.contains("android:foregroundServiceType=\"0x2\""))
        assertTrue(text.contains("android:maxSdkVersion=\"32\""))
        assertTrue(text.trimEnd().endsWith("</manifest>"))
    }

    @Test
    fun `an unreadable manifest is plain rather than broken`() {
        assertNull(BinaryXml.parse(ByteArray(40) { 9 }))
        val facts = ManifestFacts.of(null, "com.x")
        assertFalse(facts.decoded)
        assertEquals(BackupRules.Absent, facts.fullBackupContent)
    }
}
