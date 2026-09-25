package com.devbangs.onedevs

import com.devbangs.onedevs.ui.navigation.Badge
import com.devbangs.onedevs.ui.navigation.Board
import com.devbangs.onedevs.ui.navigation.Lab
import com.devbangs.onedevs.ui.navigation.Launches
import com.devbangs.onedevs.ui.navigation.Missions
import com.devbangs.onedevs.ui.navigation.TopLevel
import com.devbangs.onedevs.ui.navigation.Wallet
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation bar is driven entirely by this enum, so a duplicate route or
 * a repeated icon is a bar where two tabs light at once or neither does. None
 * of it needs a device: these are Ints and objects.
 */
class TopLevelTest {

    @Test
    fun `every tab has its own route`() {
        val routes = TopLevel.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `every tab has its own label`() {
        val labels = TopLevel.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `selected and unselected icons differ`() {
        // Selection is carried by the icon swapping weight. If the two are the
        // same drawable the tab looks identical either way, which is the bug
        // this catches and the eye does not.
        TopLevel.entries.forEach { assertNotEquals(it.icon, it.iconSelected) }
    }

    @Test
    fun `no icon is shared between tabs`() {
        val icons = TopLevel.entries.map { it.icon }
        assertEquals(icons.size, icons.distinct().size)
    }

    @Test
    fun `board is first, because it is the start destination`() {
        assertEquals(Board, TopLevel.entries.first().route)
    }
}

/**
 * Type-safe navigation builds its routes out of each destination's serial
 * name, and R8 is enabled for release. An obfuscated serial name is a route
 * that resolves in debug and not in release -- the worst shape of bug there
 * is, because every build you test by hand is the one that works.
 *
 * This asserts the names rather than a round trip: the name is the part
 * navigation actually depends on, and it is the part shrinking changes.
 */
class RouteSerializationTest {

    @Test
    fun `every route keeps its fully qualified serial name`() {
        val expected = mapOf(
            serializer<Board>() to "com.devbangs.onedevs.ui.navigation.Board",
            serializer<Missions>() to "com.devbangs.onedevs.ui.navigation.Missions",
            serializer<Launches>() to "com.devbangs.onedevs.ui.navigation.Launches",
            serializer<Lab>() to "com.devbangs.onedevs.ui.navigation.Lab",
            serializer<Badge>() to "com.devbangs.onedevs.ui.navigation.Badge",
            serializer<Wallet>() to "com.devbangs.onedevs.ui.navigation.Wallet",
        )
        expected.forEach { (s, name) -> assertEquals(name, s.descriptor.serialName) }
    }

    @Test
    fun `serial names are unique`() {
        val names = listOf(
            serializer<Board>(), serializer<Missions>(), serializer<Launches>(),
            serializer<Lab>(), serializer<Badge>(), serializer<Wallet>(),
        ).map { it.descriptor.serialName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `every top-level route is one of the serializable destinations`() {
        val known = setOf<Any>(Board, Missions, Launches, Lab, Badge)
        TopLevel.entries.forEach { assertTrue("${it.name} is not a known route", it.route in known) }
    }
}
