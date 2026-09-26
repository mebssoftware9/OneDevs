package com.devbangs.onedevs

import com.devbangs.onedevs.notifications.DevBot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel ids are written into the system the first time the app starts and
 * are permanent from then on: a channel cannot be renamed or re-identified
 * later, only deleted, and deleting one loses whatever the user chose for it.
 * A duplicate or an empty id is therefore not a bug that can be fixed in the
 * next release.
 */
class DevBotChannelTest {

    @Test
    fun `channel ids are distinct`() {
        val ids = DevBot.Channel.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `channel ids are stable identifiers, not labels`() {
        DevBot.Channel.entries.forEach {
            assertTrue("${it.name} has a blank id", it.id.isNotBlank())
            assertTrue("${it.name} id has whitespace: '${it.id}'", it.id.none(Char::isWhitespace))
            assertEquals("${it.name} id is not lowercase", it.id.lowercase(), it.id)
        }
    }

    @Test
    fun `every channel is named and described`() {
        // Zero is what an unresolved R reference compiles to, and a channel
        // with no title shows up in settings as the package name.
        DevBot.Channel.entries.forEach {
            assertTrue("${it.name} has no title", it.title != 0)
            assertTrue("${it.name} has no description", it.description != 0)
        }
    }

    @Test
    fun `channels are split by subject, so there are at least three`() {
        // The point of the split is that reward chatter can be silenced
        // without losing a mission running out of days. One channel cannot do
        // that, and this fails if the set is ever collapsed.
        assertTrue(DevBot.Channel.entries.size >= 3)
    }
}
