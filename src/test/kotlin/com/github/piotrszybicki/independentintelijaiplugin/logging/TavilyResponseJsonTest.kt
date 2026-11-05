package com.github.piotrszybicki.independentintelijaiplugin.logging

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyResponseJsonTest {
    @Test
    fun `keeps JSON responses as structured JSON`() {
        val result = JsonParser.parseString(tavilyResponseJson("""{"results":[{"url":"https://example.com"}]}"""))
        assertEquals(
            "https://example.com",
            result.asJsonObject
                .getAsJsonArray("results")[0]
                .asJsonObject
                .get("url")
                .asString,
        )
        assertTrue(JsonParser.parseString(tavilyResponseJson("null")).isJsonNull)
    }

    @Test
    fun `preserves non JSON and truncated responses as strings`() {
        for (body in listOf("", "quota exceeded", "{\"results\":[")) {
            assertEquals(body, JsonParser.parseString(tavilyResponseJson(body)).asString)
        }
    }
}
