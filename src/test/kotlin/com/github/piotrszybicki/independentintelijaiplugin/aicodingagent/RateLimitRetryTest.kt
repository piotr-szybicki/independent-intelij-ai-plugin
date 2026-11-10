package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateLimitRetryTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Test
    fun `uses bounded exponential delay without a usable header`() {
        assertEquals(1000L, rateLimitRetryDelay(null, 0, now))
        assertEquals(2000L, rateLimitRetryDelay("invalid", 1, now))
        assertEquals(4000L, rateLimitRetryDelay(null, 2, now))
        assertEquals(8000L, rateLimitRetryDelay(null, 3, now))
        assertEquals(60000L, rateLimitRetryDelay(null, 9, now))
        assertNull(rateLimitRetryDelay(null, 10, now))
    }

    @Test
    fun `honors seconds and HTTP dates without shortening long waits`() {
        assertEquals(3000L, rateLimitRetryDelay("3", 0, now))
        assertEquals(5000L, rateLimitRetryDelay("Tue, 10 Nov 2026 12:00:05 GMT", 0, now))
        assertEquals(1000L, rateLimitRetryDelay("Tue, 10 Nov 2026 11:59:59 GMT", 0, now))
        assertEquals(4000L, rateLimitRetryDelay("0", 2, now))
        assertNull(rateLimitRetryDelay("61", 0, now))
        assertNull(rateLimitRetryDelay("Tue, 10 Nov 2026 12:01:01 GMT", 0, now))
    }
}
