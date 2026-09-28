package com.devbangs.onedevs

import com.devbangs.onedevs.ui.badges.BadgeCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge catalogue is static content, which makes it the kind of thing
 * nobody re-reads after writing it. A badge listed twice, or a group whose
 * count is a lie, would look plausible on screen for a long time.
 */
class BadgeCatalogueTest {

    @Test
    fun `no badge appears twice`() {
        val names = BadgeCatalogue.flatMap { group -> group.badges.map { it.name } }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("badge name ids used more than once: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every group has badges in it`() {
        BadgeCatalogue.forEach { assertTrue("a group has no badges", it.badges.isNotEmpty()) }
    }

    @Test
    fun `every group has its own name`() {
        val names = BadgeCatalogue.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `nothing references a missing resource`() {
        // Zero is what an unresolved R reference compiles to. A real id never
        // is, so this catches a badge wired to a resource that was renamed.
        BadgeCatalogue.forEach { group ->
            assertTrue(group.icon != 0 && group.name != 0 && group.tagline != 0)
            group.badges.forEach {
                assertTrue(it.icon != 0 && it.name != 0 && it.requirement != 0)
            }
        }
    }
}
