package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionContextTest {
    @Test
    fun `existing tools still execute with a context`() {
        val tool =
            object : AICodingAgentTool {
                override val name = "example"
                override val description = "example"
                override val inputSchema = JsonObject()

                override fun execute(input: JsonObject) = input.get("value").asString
            }
        val input = JsonObject().apply { addProperty("value", "result") }
        assertEquals("result", tool.execute(input, ToolExecutionContext("conversation", "request", "tool")))
    }
}
