package com.devbangs.onedevs.data.feedback

import com.devbangs.onedevs.data.badges.BadgeState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `reports decode as the server sends them`() {
        val reports = json.decodeFromString(
            ListSerializer(FeedbackReport.serializer()),
            """
            [{"id":2,"kind":"bug","body":"Crashes when rotated on the settings screen","status":"confirmed",
              "at":"2026-10-03T10:00:00+00:00","mine":false,"author":"ada"},
             {"id":1,"kind":"suggestion","body":"A dark mode would help at night","status":"new","mine":true}]
            """,
        )
        assertTrue(reports[0].isBug)
        assertEquals("confirmed", reports[0].status)
        assertFalse(reports[1].isBug)
        assertTrue(reports[1].mine)
    }

    @Test
    fun `badges decode with their progress`() {
        val badges = json.decodeFromString(
            ListSerializer(BadgeState.serializer()),
            """[{"key":"useful","earned":false,"have":2,"need":5},{"key":"device","earned":true,"have":1,"need":1}]""",
        )
        assertEquals(2, badges.first().have)
        assertTrue(badges.last().earned)
    }
}
