package com.devbangs.onedevs.lab

import java.util.Locale

/** One attribute of a compiled XML element, typed the way aapt2 stored it. */
data class XmlAttr(
    val uri: String?,
    val name: String,
    val type: Int,
    val data: Int,
    /** The string, for string values; null otherwise. */
    val string: String?,
) {
    val bool: Boolean? get() = if (type == BinaryXml.TYPE_BOOLEAN) data != 0 else null
    val int: Int? get() = if (type == BinaryXml.TYPE_INT_DEC || type == BinaryXml.TYPE_INT_HEX) data else null
    val isReference: Boolean get() = type == BinaryXml.TYPE_REFERENCE
    val text: String? get() = if (type == BinaryXml.TYPE_STRING) string else null
}

/** An element of a compiled XML document, with its children. */
class XmlElement(val name: String, val attrs: List<XmlAttr>, val children: List<XmlElement>) {

    /**
     * An attribute by local name. The android namespace first, then any: a
     * manifest put through an obfuscator sometimes loses its namespaces but
     * keeps the names.
     */
    fun attr(name: String): XmlAttr? =
        attrs.firstOrNull { it.name == name && it.uri == BinaryXml.ANDROID } ?: attrs.firstOrNull { it.name == name }

    fun children(name: String): List<XmlElement> = children.filter { it.name == name }

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
}

/**
 * Android's compiled XML, the format AndroidManifest.xml is stored in inside
 * an APK.
 *
 * PackageManager parses the manifest too, and its answers are the ones the
 * device acts on -- so the analyzer asks it first. But it only exposes what
 * its public API happens to have fields for. Whether there is a network
 * security config, which backup rules apply, which foreground service types a
 * service declares, how high a permission request reaches: those are in the
 * file and nowhere else. And a manifest inspector that cannot show the
 * manifest is not one.
 *
 * The format is a sequence of chunks: a string pool, a map from strings to
 * attribute IDs, then start and end events for namespaces and elements.
 */
internal object BinaryXml {

    const val ANDROID = "http://schemas.android.com/apk/res/android"

    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRINGS = 0x0001
    private const val CHUNK_NS_START = 0x0100
    private const val CHUNK_ELEMENT_START = 0x0102
    private const val CHUNK_ELEMENT_END = 0x0103
    private const val UTF8_FLAG = 0x100
    private const val NONE = -1

    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_BOOLEAN = 0x12
    private const val TYPE_COLOR_FIRST = 0x1c
    private const val TYPE_COLOR_LAST = 0x1f

    private class Building(val name: String, val attrs: List<XmlAttr>) {
        val children = mutableListOf<XmlElement>()
        fun done() = XmlElement(name, attrs, children.toList())
    }

    /** The root element, or null if these bytes are not compiled XML. */
    fun parse(bytes: ByteArray): XmlElement? = runCatching { parseOrThrow(bytes) }.getOrNull()

    /** Namespace prefixes, as the document declared them. */
    fun prefixes(bytes: ByteArray): Map<String, String> = runCatching {
        val out = LinkedHashMap<String, String>()
        var strings: List<String> = emptyList()
        walk(bytes) { type, at, _ ->
            when (type) {
                CHUNK_STRINGS -> strings = stringPool(bytes, at)
                CHUNK_NS_START -> {
                    val prefix = strings.getOrNull(u32(bytes, at + 16).toInt())
                    val uri = strings.getOrNull(u32(bytes, at + 20).toInt())
                    if (prefix != null && uri != null) out[uri] = prefix
                }
            }
        }
        out.toMap()
    }.getOrDefault(emptyMap())

    private fun parseOrThrow(bytes: ByteArray): XmlElement? {
        if (bytes.size < 8 || u16(bytes, 0) != CHUNK_XML) return null
        var strings: List<String> = emptyList()
        val stack = ArrayDeque<Building>()
        var root: XmlElement? = null
        walk(bytes) { type, at, headerSize ->
            when (type) {
                CHUNK_STRINGS -> strings = stringPool(bytes, at)
                CHUNK_ELEMENT_START -> {
                    val ext = at + headerSize
                    val name = strings.getOrNull(u32(bytes, ext + 4).toInt()).orEmpty()
                    val attrStart = u16(bytes, ext + 8)
                    val attrSize = u16(bytes, ext + 10)
                    val attrCount = u16(bytes, ext + 12)
                    val attrs = (0 until attrCount).map { i ->
                        val a = ext + attrStart + i * attrSize
                        val ns = u32(bytes, a).toInt()
                        val raw = u32(bytes, a + 8).toInt()
                        val dataType = bytes[a + 15].toInt() and 0xFF
                        val data = u32(bytes, a + 16).toInt()
                        XmlAttr(
                            uri = if (ns == NONE) null else strings.getOrNull(ns),
                            name = strings.getOrNull(u32(bytes, a + 4).toInt()).orEmpty(),
                            type = dataType,
                            data = data,
                            string = if (dataType == TYPE_STRING) {
                                strings.getOrNull(if (raw != NONE) raw else data)
                            } else {
                                null
                            },
                        )
                    }
                    stack.addLast(Building(name, attrs))
                }
                CHUNK_ELEMENT_END -> {
                    val done = stack.removeLastOrNull()?.done() ?: return@walk
                    val parent = stack.lastOrNull()
                    if (parent == null) root = root ?: done else parent.children += done
                }
            }
        }
        return root
    }

    /** Calls [visit] with each chunk's type, offset and header size. */
    private inline fun walk(bytes: ByteArray, visit: (Int, Int, Int) -> Unit) {
        var at = u16(bytes, 2)
        while (at + 8 <= bytes.size) {
            val type = u16(bytes, at)
            val headerSize = u16(bytes, at + 2)
            val size = u32(bytes, at + 4).toInt()
            if (size < 8 || at + size > bytes.size) break
            visit(type, at, headerSize)
            at += size
        }
    }

    private fun stringPool(b: ByteArray, at: Int): List<String> {
        val headerSize = u16(b, at + 2)
        val count = u32(b, at + 8).toInt()
        val flags = u32(b, at + 16).toInt()
        val stringsStart = u32(b, at + 20).toInt()
        val utf8 = flags and UTF8_FLAG != 0
        return (0 until count).map { i ->
            val offset = u32(b, at + headerSize + i * 4).toInt()
            val s = at + stringsStart + offset
            if (utf8) utf8String(b, s) else utf16String(b, s)
        }
    }

    private fun utf8String(b: ByteArray, start: Int): String {
        var at = start
        // Two lengths: characters, then bytes. Each is one byte, or two with
        // the high bit set on the first.
        at += if (b[at].toInt() and 0x80 != 0) 2 else 1
        val high = b[at].toInt() and 0xFF
        val length = if (high and 0x80 != 0) {
            ((high and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)
        } else {
            high
        }
        at += if (high and 0x80 != 0) 2 else 1
        return String(b, at, length, Charsets.UTF_8)
    }

    private fun utf16String(b: ByteArray, start: Int): String {
        var at = start
        val first = u16(b, at)
        val length = if (first and 0x8000 != 0) {
            ((first and 0x7FFF) shl 16) or u16(b, at + 2)
        } else {
            first
        }
        at += if (first and 0x8000 != 0) 4 else 2
        return String(b, at, length * 2, Charsets.UTF_16LE)
    }

    /**
     * Back to text, as close to the source as the compiled form allows.
     *
     * References come out as @0x7f... unless [resolve] can name them. That
     * needs the APK's resource table, which the analyzer has and this file
     * deliberately does not.
     */
    fun render(
        root: XmlElement,
        prefixes: Map<String, String>,
        resolve: (Int) -> String? = { null },
    ): String {
        val out = StringBuilder()
        fun attrName(a: XmlAttr): String {
            val prefix = a.uri?.let { prefixes[it] ?: if (it == ANDROID) "android" else null }
            return if (prefix.isNullOrEmpty()) a.name else "$prefix:${a.name}"
        }
        fun element(e: XmlElement, depth: Int, top: Boolean) {
            val pad = "    ".repeat(depth)
            out.append(pad).append('<').append(e.name)
            val lines = buildList {
                if (top) prefixes.forEach { (uri, prefix) -> add("xmlns:$prefix=\"${escape(uri)}\"") }
                e.attrs.forEach { add("${attrName(it)}=\"${escape(value(it, resolve))}\"") }
            }
            when {
                lines.isEmpty() -> Unit
                lines.size == 1 -> out.append(' ').append(lines[0])
                else -> lines.forEach { out.append('\n').append(pad).append("    ").append(it) }
            }
            if (e.children.isEmpty()) {
                out.append(" />\n")
            } else {
                out.append(">\n")
                e.children.forEach { element(it, depth + 1, false) }
                out.append(pad).append("</").append(e.name).append(">\n")
            }
        }
        element(root, 0, true)
        return out.toString().trimEnd()
    }

    fun value(a: XmlAttr, resolve: (Int) -> String? = { null }): String = when (a.type) {
        TYPE_STRING -> a.string.orEmpty()
        TYPE_BOOLEAN -> (a.data != 0).toString()
        TYPE_INT_DEC -> a.data.toString()
        TYPE_INT_HEX -> "0x" + Integer.toHexString(a.data)
        TYPE_REFERENCE -> resolve(a.data) ?: "@0x%08x".format(Locale.ROOT, a.data)
        TYPE_ATTRIBUTE -> "?0x%08x".format(Locale.ROOT, a.data)
        TYPE_FLOAT -> Float.fromBits(a.data).toString()
        TYPE_DIMENSION -> complex(a.data, listOf("px", "dp", "sp", "pt", "in", "mm"), 1f)
        TYPE_FRACTION -> complex(a.data, listOf("%", "%p"), 100f)
        in TYPE_COLOR_FIRST..TYPE_COLOR_LAST -> "#%08x".format(Locale.ROOT, a.data)
        else -> "0x%08x".format(Locale.ROOT, a.data)
    }

    private fun complex(data: Int, units: List<String>, scale: Float): String {
        val radix = listOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31).toFloat())
        val value = (data and 0xFFFFFF00.toInt()) * radix[(data shr 4) and 3] * scale
        val unit = units.getOrElse(data and 0xF) { "" }
        val number = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        return number + unit
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** How backup is configured beyond the allowBackup flag. */
enum class BackupRules { Absent, Rules, Disabled }

/**
 * What the manifest says that PackageManager does not.
 *
 * Every field defaults to what an absent attribute means, so a manifest that
 * could not be decoded reads as a plain one rather than as a broken one.
 */
data class ManifestFacts(
    val decoded: Boolean = false,
    val networkSecurityConfig: Boolean = false,
    val dataExtractionRules: Boolean = false,
    val fullBackupContent: BackupRules = BackupRules.Absent,
    val requestLegacyExternalStorage: Boolean = false,
    val roundIcon: Boolean = false,
    val localeConfig: Boolean = false,
    /** The application's own android:resizeableActivity, when set. */
    val resizeable: Boolean? = null,
    /** Permission to the highest API level it is requested on. */
    val permissionMaxSdk: Map<String, Int> = emptyMap(),
    val queries: Boolean = false,
    /** Activities reached from the launcher, aliases resolved to their targets. */
    val launchers: Set<String> = emptySet(),
    /** Service to its foregroundServiceType flags. */
    val serviceTypes: Map<String, Int> = emptyMap(),
    /** Activity to its own android:resizeableActivity, when set. */
    val activityResizeable: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        fun of(root: XmlElement?, packageName: String): ManifestFacts {
            if (root == null) return ManifestFacts()
            val app = root.child("application")
            fun qualify(name: String?): String = when {
                name.isNullOrEmpty() -> ""
                name.startsWith(".") -> packageName + name
                '.' !in name -> "$packageName.$name"
                else -> name
            }
            val permissionMax = (root.children("uses-permission") + root.children("uses-permission-sdk-23"))
                .mapNotNull { p ->
                    val name = p.attr("name")?.text ?: return@mapNotNull null
                    val max = p.attr("maxSdkVersion")?.int ?: return@mapNotNull null
                    name to max
                }.toMap()
            val launchers = buildSet {
                val activities = app?.children("activity").orEmpty() + app?.children("activity-alias").orEmpty()
                activities.forEach { a ->
                    val launches = a.children("intent-filter").any { f ->
                        f.children("action").any { it.attr("name")?.text == "android.intent.action.MAIN" } &&
                            f.children("category").any {
                                it.attr("name")?.text == "android.intent.category.LAUNCHER"
                            }
                    }
                    if (launches) {
                        add(qualify(a.attr("name")?.text))
                        a.attr("targetActivity")?.text?.let { add(qualify(it)) }
                    }
                }
            }
            val backup = app?.attr("fullBackupContent")
            return ManifestFacts(
                decoded = true,
                networkSecurityConfig = app?.attr("networkSecurityConfig") != null,
                dataExtractionRules = app?.attr("dataExtractionRules") != null,
                fullBackupContent = when {
                    backup == null -> BackupRules.Absent
                    backup.bool == false -> BackupRules.Disabled
                    else -> BackupRules.Rules
                },
                requestLegacyExternalStorage = app?.attr("requestLegacyExternalStorage")?.bool == true,
                roundIcon = app?.attr("roundIcon") != null,
                localeConfig = app?.attr("localeConfig") != null,
                resizeable = app?.attr("resizeableActivity")?.bool,
                permissionMaxSdk = permissionMax,
                queries = root.child("queries") != null,
                launchers = launchers - "",
                serviceTypes = app?.children("service").orEmpty().mapNotNull { s ->
                    val types = s.attr("foregroundServiceType")?.int ?: return@mapNotNull null
                    qualify(s.attr("name")?.text) to types
                }.toMap(),
                activityResizeable = app?.children("activity").orEmpty().mapNotNull { a ->
                    val r = a.attr("resizeableActivity")?.bool ?: return@mapNotNull null
                    qualify(a.attr("name")?.text) to r
                }.toMap(),
            )
        }
    }
}

// Doors for the tests.
internal fun parseXmlForTest(bytes: ByteArray) = BinaryXml.parse(bytes)

internal fun renderXmlForTest(bytes: ByteArray): String? =
    BinaryXml.parse(bytes)?.let { BinaryXml.render(it, BinaryXml.prefixes(bytes)) }
