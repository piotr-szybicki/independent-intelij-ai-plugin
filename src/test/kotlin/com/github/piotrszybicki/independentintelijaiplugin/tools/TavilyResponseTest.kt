package com.github.piotrszybicki.independentintelijaiplugin.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class TavilyResponseTest {
    private class FakeConnection(
        private val success: String,
        private val failure: String?,
    ) : HttpURLConnection(URL("https://example.com")) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getInputStream(): InputStream = ByteArrayInputStream(success.toByteArray(Charsets.UTF_8))

        override fun getErrorStream(): InputStream? =
            failure?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun `reads the successful response body`() {
        assertEquals("{\"results\":[]}", readTavilyResponse(FakeConnection("{\"results\":[]}", null), 200))
    }

    @Test
    fun `reads the error body when the request fails`() {
        assertEquals(
            "{\"error\":\"quota\"}",
            readTavilyResponse(FakeConnection("success", "{\"error\":\"quota\"}"), 429),
        )
        assertNull(readTavilyResponse(FakeConnection("success", null), 500))
    }
}
