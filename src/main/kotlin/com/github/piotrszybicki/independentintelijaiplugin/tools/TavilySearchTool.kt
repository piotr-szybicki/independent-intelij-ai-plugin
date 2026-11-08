package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ToolExecutionContext
import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.EnvironmentToken
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI

class TavilySearchTool(
    private val project: Project,
) : AICodingAgentTool {
    override val name = "web_serch"
    override val description =
        "Search the web for current information using Tavily. Results contain source URLs and excerpts."
    override val inputSchema =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "query",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "The web search query.")
                        },
                    )
                },
            )
            add("required", JsonArray().apply { add("query") })
        }

    override fun execute(input: JsonObject): String = execute(input, ToolExecutionContext("", "", ""))

    override fun execute(
        input: JsonObject,
        context: ToolExecutionContext,
    ): String {
        val query =
            input
                .get("query")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
        if (query.isNullOrEmpty()) return "Error: query must be a non-empty string."
        val key =
            runCatching { tavilyToken(AgentConfigurations.getInstance(project).text().orEmpty()) }.getOrElse {
                return "Error: Could not read the Tavily configuration."
            }
        if (key.isEmpty()) return "Error: Set tavily.token in the JSON configuration to use web search."
        var connection: HttpURLConnection? = null
        var status: Int? = null
        var error: String? = null
        var responseBody: String? = null
        val started = System.currentTimeMillis()
        return try {
            connection = URI("https://api.tavily.com/search").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            val body =
                JsonObject().apply {
                    addProperty("query", query)
                    addProperty("max_results", 5)
                    addProperty("search_depth", "basic")
                }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseStatus = connection.responseCode
            status = responseStatus
            responseBody = readTavilyResponse(connection, responseStatus)?.replace(key, "[REDACTED]")
            if (responseStatus !in 200..299) {
                "Error: Tavily search failed (HTTP $responseStatus)."
            } else {
                responseBody.orEmpty().take(50000)
            }
        } catch (e: java.io.IOException) {
            error = e.javaClass.simpleName
            "Error: Tavily search request failed."
        } finally {
            connection?.disconnect()
            ModelUsageDatabase.recordTavilyCall(
                context,
                query,
                status,
                System.currentTimeMillis() - started,
                error,
                responseBody,
            )
        }
    }
}

internal fun readTavilyResponse(
    connection: HttpURLConnection,
    status: Int,
): String? {
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
}

internal fun tavilyToken(
    text: String,
    environment: (String) -> String? = System::getenv,
): String {
    if (text.isBlank()) return ""
    val root = JsonParser.parseString(text)
    if (!root.isJsonObject) return ""
    val section = root.asJsonObject.get("tavily") ?: return ""
    require(section.isJsonObject) { "tavily must be an object" }
    val value = section.asJsonObject.get("token") ?: return ""
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "tavily.token must be a string" }
    val token = value.asString.trim()
    return EnvironmentToken.variableName(token)?.let { environment(it)?.trim().orEmpty() } ?: token
}
