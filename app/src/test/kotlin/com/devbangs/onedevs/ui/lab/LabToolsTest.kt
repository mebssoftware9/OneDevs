package com.devbangs.onedevs.ui.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue says what can run; ToolKind says what does. Nothing else
 * links them, and the failure modes are both quiet: a tool marked available
 * that was never wired up is a row that ignores taps, and a tool wired up
 * under a name the catalogue spells differently is one nobody can reach.
 */
class LabToolsTest {

    private val available = LabCatalogue.flatMap { it.tools }
        .filter { it.status == LabStatus.Available }
        .map { it.name }

    @Test
    fun `every available tool runs`() {
        val unwired = available.filter { ToolKind.of(it) == null }
        assertTrue("marked available but not wired: $unwired", unwired.isEmpty())
    }

    @Test
    fun `every wired tool is in the catalogue as available`() {
        val orphans = ToolKind.entries.map { it.toolName }.filter { it !in available }
        assertTrue("wired but not offered: $orphans", orphans.isEmpty())
    }

    @Test
    fun `no tool name is used twice`() {
        assertEquals(available.size, available.distinct().size)
        assertEquals(ToolKind.entries.size, ToolKind.entries.map { it.toolName }.distinct().size)
    }
}
