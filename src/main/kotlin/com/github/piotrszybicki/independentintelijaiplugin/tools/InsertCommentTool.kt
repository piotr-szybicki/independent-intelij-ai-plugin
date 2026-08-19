package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.cleanup.CommentMarker
import com.github.piotrszybicki.independentintelijaiplugin.logging.CommentDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

class InsertCommentTool(private val project: Project) : AICodingAgentTool {

    override val name = "insert_comment"
    override val description =
        "Stores a documentation comment and returns its id. Documentation is kept in the database, " +
            "not in the files: pass the comment text here, then write the returned " +
            "\"// comment_id: <id>\" line where the documentation belongs. Never write a /** */ " +
            "block into a file."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("comment", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The comment text. Write it as the /** */ block it replaces, so it reads " +
                        "correctly when it is put back into the code.",
                )
            })
        })
        add("required", JsonArray().apply { add("comment") })
    }

    override fun execute(input: JsonObject): String {
        val comment = input.get("comment")?.asString ?: return "Error: missing 'comment'"
        if (comment.isBlank()) return "Error: 'comment' is empty; there is nothing to store"

        return try {
            val id = CommentDatabase.insert(project, comment)
            "Stored as comment id $id. Write \"${CommentMarker.of(id)}\" where the documentation belongs."
        } catch (e: CommentDatabase.Unavailable) {
            "Error: ${e.message}"
        } catch (e: Exception) {
            "Error: the comment could not be stored: ${e.message}"
        }
    }
}
