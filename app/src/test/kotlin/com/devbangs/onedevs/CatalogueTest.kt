package com.devbangs.onedevs

import com.devbangs.onedevs.ui.badges.BadgeCatalogue
import com.devbangs.onedevs.ui.board.LiveApps
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

/**
 * Live Apps deep-links into Play by package name, so a typo is a row that
 * opens a store page for an app that does not exist.
 */
class LiveAppsTest {

    @Test
    fun `package names are unique`() {
        val packages = LiveApps.map { it.packageName }
        assertEquals(packages.size, packages.distinct().size)
    }

    @Test
    fun `package names are well formed`() {
        // Two or more dot-separated segments, each starting with a letter.
        // Play rejects anything else, and so should a listing typed by hand.
        val shape = Regex("""[a-zA-Z][\w]*(\.[a-zA-Z][\w]*)+""")
        LiveApps.forEach {
            assertTrue("${it.packageName} is not a package name", shape.matches(it.packageName))
        }
    }

    @Test
    fun `every app is named and rewarded`() {
        LiveApps.forEach {
            assertTrue("${it.packageName} has no name", it.name.isNotBlank())
            assertTrue("${it.packageName} rewards ${it.reward}", it.reward > 0)
        }
    }
}
