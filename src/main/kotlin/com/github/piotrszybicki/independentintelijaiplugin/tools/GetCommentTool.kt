package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

/**
 * Reads back a comment a `// comment_id: N` marker stands for.
 *
 * Without it a swept file is unreadable in the way that matters: the code is all there, and every
 * word anyone wrote about why it is like that has become a number.
 */
class GetCommentTool(private val project: Project) : AICodingAgentTool {

    override val name = "get_comment"
    override val description =
        "Returns the documentation comment stored under an id. A \"// comment_id: N\" line in a " +
            "file is a stored comment: call this with N to read what it says."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("id", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "The number from the comment_id marker")
            })
        })
        add("required", JsonArray().apply { add("id") })
    }

    override fun execute(input: JsonObject): String {
        val id = input.get("id")?.asLong ?: return "Error: missing 'id'"

        return try {
            CommentDatabase.read(project, id)
                ?: "No comment is stored under id $id."
        } catch (e: CommentDatabase.Unavailable) {
            "Error: ${e.message}"
        } catch (e: Exception) {
            "Error: the comment could not be read: ${e.message}"
        }
    }
}
