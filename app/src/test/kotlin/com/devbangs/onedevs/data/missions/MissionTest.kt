package com.devbangs.onedevs.data.missions

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mission(state: String) = json.decodeFromString(
        Mission.serializer(),
        """
        {"id":"m","name":"Mission Ember","state":"$state","member":true,"seats":[
          {"seat":1,"listing":"a","title":"Mine","mine":true,"tested":2},
          {"seat":2,"listing":"b","title":"B","owner_name":"ben","package_name":"com.b",
           "done_today":false,"done_ever":true,"tested":1},
          {"seat":3,"listing":"c","title":"C","done_today":true,"done_ever":true}
        ]}
        """,
    )

    @Test
    fun `your own seat is not a task`() {
        assertEquals(listOf("b", "c"), mission("recruiting").others.map { it.listing })
    }

    @Test
    fun `while filling, having used an app at all counts`() {
        assertEquals(2, mission("recruiting").tasksDone)
    }

    @Test
    fun `once running, only today counts`() {
        assertEquals(1, mission("running").tasksDone)
    }

    @Test
    fun `a seat from a non-member reply has no private fields`() {
        val seat = json.decodeFromString(MissionSeat.serializer(), """{"seat":1,"title":"X"}""")
        assertNull(seat.packageName)
        assertNull(seat.tested)
    }

    @Test
    fun `a server line decodes`() {
        val line = json.decodeFromString(
            MissionMessage.serializer(),
            """{"id":7,"kind":"join","body":"Focus Ocean","author":"ben","seat":2,"mine":false}""",
        )
        assertEquals("join", line.kind)
        assertEquals(2, line.seat)
    }
}
