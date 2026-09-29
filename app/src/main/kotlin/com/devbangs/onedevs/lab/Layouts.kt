package com.devbangs.onedevs.lab

/**
 * Reads compiled layouts for the things a screen reader, a large font size or
 * a thumb would run into.
 *
 * Static and partial by nature: a contentDescription set in code, a text size
 * from a style, a Compose screen -- none of those are in a layout file. What
 * is here is certain, though. An ImageView declared with no description and
 * not marked unimportant is announced as "unlabelled" by TalkBack unless code
 * fixes it.
 */
internal object Layouts {

    /** Material's minimum touch target. */
    private const val MIN_TARGET_DP = 48f

    private const val IMPORTANT_NO = 2
    private const val IMPORTANT_NO_HIDE_DESCENDANTS = 4

    private const val UNIT_DP = 1
    private const val UNIT_SP = 2

    private val IMAGES = listOf("ImageView", "ImageButton", "FloatingActionButton")
    private val TAPPABLE = listOf("Button", "ImageButton", "CheckBox", "Switch", "RadioButton", "Chip")

    /** Whether a compiled XML file is a layout rather than a drawable, menu or config. */
    fun isLayout(root: XmlElement): Boolean = has(root) { it.attr("layout_width") != null }

    fun scan(documents: List<Pair<String, XmlElement>>): LayoutStats {
        var files = 0
        var images = 0
        var unlabelled = 0
        var sp = 0
        var fixed = 0
        var small = 0
        val examples = LinkedHashSet<String>()
        documents.forEach { (path, root) ->
            if (!isLayout(root)) return@forEach
            files++
            walk(root) { e ->
                val simple = e.name.substringAfterLast('.')
                if (IMAGES.any { simple.endsWith(it) }) {
                    images++
                    val importance = e.attr("importantForAccessibility")?.int
                    val labelled = e.attr("contentDescription") != null ||
                        importance == IMPORTANT_NO || importance == IMPORTANT_NO_HIDE_DESCENDANTS
                    if (!labelled) {
                        unlabelled++
                        examples += path
                    }
                }
                e.attr("textSize")?.dimension?.let { (_, unit) ->
                    if (unit == UNIT_SP) {
                        sp++
                    } else {
                        fixed++
                        examples += path
                    }
                }
                val tappable = e.attr("clickable")?.bool == true || TAPPABLE.any { simple.endsWith(it) }
                if (tappable && tooSmall(e)) {
                    small++
                    examples += path
                }
            }
        }
        return LayoutStats(files, images, unlabelled, sp, fixed, small, examples.take(6))
    }

    /**
     * Sized in dp below 48 in either direction, with no minimum that makes up
     * for it. wrap_content and match_parent are not dimensions and are left
     * alone: how big they end up is decided at runtime.
     */
    private fun tooSmall(e: XmlElement): Boolean {
        fun dp(name: String) = e.attr(name)?.dimension?.takeIf { it.second == UNIT_DP }?.first
        val height = dp("layout_height")
        val width = dp("layout_width")
        val minHeight = dp("minHeight") ?: 0f
        val minWidth = dp("minWidth") ?: 0f
        return (height != null && height < MIN_TARGET_DP && minHeight < MIN_TARGET_DP) ||
            (width != null && width < MIN_TARGET_DP && minWidth < MIN_TARGET_DP)
    }

    private fun walk(e: XmlElement, visit: (XmlElement) -> Unit) {
        visit(e)
        e.children.forEach { walk(it, visit) }
    }

    private fun has(e: XmlElement, test: (XmlElement) -> Boolean): Boolean =
        test(e) || e.children.any { has(it, test) }
}
