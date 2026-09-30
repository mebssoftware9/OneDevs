package com.devbangs.onedevs.data.backend

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietRequestTest {

    private suspend fun quiet() = coroutineContext[QuietRequest] != null

    @Test
    fun `plain calls are not quiet`() = runBlocking {
        assertFalse(quiet())
    }

    @Test
    fun `quietly marks everything inside it`() = runBlocking {
        assertTrue(quietly { quiet() })
        assertTrue(quietly { quietly(quiet = false) { quiet() } })
    }

    @Test
    fun `quiet false leaves the call loud`() = runBlocking {
        assertFalse(quietly(quiet = false) { quiet() })
    }
}
