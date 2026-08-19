package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ActionCallback
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class AttachLibrarySourcesTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val EP_NAME = "com.intellij.attachSourcesProvider"
        private const val MAX_CANDIDATES = 20

        private const val DEFAULT_TIMEOUT_SECONDS = 180
        private const val MAX_TIMEOUT_SECONDS = 600
    }

    override val interruptible = false

    override val name = "attach_library_sources"
    override val description =
        "Downloads and attaches a library's sources so its classes read as source instead of " +
            "decompiled bytecode. Worth it only when read_library_class reports decompiled output " +
            "and the comments or parameter names matter. Asks the user first: it downloads over " +
            "the network and changes the project's library configuration."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("class_name", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "A class from the library whose sources are wanted, simple or fully qualified",
                )
            })
            add("action", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Which attach action to run, when a previous call reported more than one. Omit " +
                        "when there is only one, which is the usual case.",
                )
            })
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for the download. Defaults to $DEFAULT_TIMEOUT_SECONDS, " +
                        "maximum $MAX_TIMEOUT_SECONDS.",
                )
            })
        })
        add("required", JsonArray().apply { add("class_name") })
    }

    override fun execute(input: JsonObject): String {
        val className = input.get("class_name")?.asString?.trim().orEmpty()
        if (className.isEmpty()) return "Error: missing 'class_name'"

        val requested = input.get("action")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val candidate = when (val resolution = LibraryClasses.resolve(project, className, MAX_CANDIDATES)) {
            is LibraryClasses.Resolution.NotFound ->
                return "No class named \"$className\" is visible to this project."

            is LibraryClasses.Resolution.Ambiguous ->
                return "\"$className\" is ambiguous — ${resolution.candidates.size} classes match:\n" +
                    resolution.candidates.joinToString("\n") { "  ${it.qualifiedName}" } +
                    "\n\nCall again with the fully qualified name."

            is LibraryClasses.Resolution.Found -> resolution.candidate
        }

        if (candidate.inProject) {
            return "${candidate.qualifiedName} is part of this project, not a library -- its source " +
                "is already there. Use read_project_file."
        }
        if (candidate.fromSources) {
            return "${candidate.qualifiedName} already has sources attached" +
                candidate.libraryName?.let { " ($it)" }.orEmpty() +
                ". read_library_class will return real source for it."
        }

        val entries = ReadAction.computeBlocking<List<LibraryOrderEntry>, RuntimeException> {
            ProjectFileIndex.getInstance(project)
                .getOrderEntriesForFile(candidate.virtualFile)
                .filterIsInstance<LibraryOrderEntry>()
        }
        if (entries.isEmpty()) {
            return "${candidate.qualifiedName} is not part of a library the project declares, so " +
                "there is nothing to attach sources to."
        }

        val actions = try {
            findActions(entries, candidate)
        } catch (e: Exception) {
            return "Error: could not ask the IDE for source-attachment actions: " +
                (e.message ?: e::class.java.simpleName)
        }

        val library = candidate.libraryName ?: entries.first().libraryName ?: "this library"

        if (actions.isEmpty()) {
            return "The IDE offers no way to attach sources for $library. That usually means the " +
                "project is not imported from a build tool that can fetch them, or the sources are " +
                "simply not published. The user can still attach a jar by hand through Project " +
                "Structure | Libraries."
        }

        val chosen = when {
            requested != null -> actions.firstOrNull { it.label.equals(requested, ignoreCase = true) }
                ?: actions.firstOrNull { it.label.contains(requested, ignoreCase = true) }
                ?: return "Error: no attach action matching \"$requested\". Available:\n" +
                    actions.joinToString("\n") { "  ${it.label}" }

            actions.size == 1 -> actions.single()

            else -> return "${actions.size} ways to attach sources for $library:\n" +
                actions.joinToString("\n") { "  ${it.label}" } +
                "\n\nCall again with 'action' set to one of these."
        }

        if (!confirm(chosen.label, library, timeoutSeconds)) {
            return "The user declined to download sources for $library. Carry on with the " +
                "decompiled version, which is usually enough to read an API."
        }

        return perform(chosen, entries, library, timeoutSeconds, candidate.qualifiedName)
    }

    private class AttachAction(val label: String, val target: Any)

    private fun findActions(
        entries: List<LibraryOrderEntry>,
        candidate: LibraryClasses.Candidate,
    ): List<AttachAction> {
        val providers = ExtensionPointName.create<Any>(EP_NAME).extensionsIfPointIsRegistered
        if (providers.isEmpty()) return emptyList()

        val found = mutableListOf<AttachAction>()
        ReadAction.runBlocking<RuntimeException> {
            for (provider in providers) {
                val actions = runCatching {
                    call(provider, "getActions", 2, entries, candidate.file) as? Collection<*>
                }.getOrNull() ?: continue

                for (action in actions.filterNotNull()) {
                    val label = runCatching { call(action, "getName", 0) as? String }.getOrNull()
                    if (label.isNullOrBlank()) continue
                    found.add(AttachAction(label, action))
                }
            }
        }
        return found
    }

    private fun perform(
        action: AttachAction,
        entries: List<LibraryOrderEntry>,
        library: String,
        timeoutSeconds: Int,
        qualifiedName: String,
    ): String {
        val callback = try {
            var result: Any? = null
            var failure: Exception? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    result = call(action.target, "perform", 1, entries)
                } catch (e: Exception) {
                    failure = e
                }
            }
            failure?.let { throw it }
            result as? ActionCallback
        } catch (e: Exception) {
            return "Error running \"${action.label}\" for $library: ${e.message ?: e::class.java.simpleName}"
        } ?: return "\"${action.label}\" was started for $library, but the IDE reported no result to " +
            "wait on. Check whether sources appeared with read_library_class."

        if (!callback.waitFor(timeoutSeconds * 1000L)) {
            return "\"${action.label}\" is still running after ${timeoutSeconds}s. It continues in " +
                "the IDE -- check again later with read_library_class."
        }
        if (callback.isRejected) {
            return "\"${action.label}\" failed for $library" +
                callback.error?.let { ": $it" }.orEmpty() +
                ". The sources may simply not be published for this version."
        }

        val now = LibraryClasses.resolve(project, qualifiedName, MAX_CANDIDATES)
        val attached = (now as? LibraryClasses.Resolution.Found)?.candidate?.fromSources == true

        return if (attached) {
            "Attached sources for $library. read_library_class will now return real source for " +
                "$qualifiedName, with its comments and Javadoc."
        } else {
            "\"${action.label}\" completed for $library, but $qualifiedName still resolves to " +
                "compiled code. The sources may not be published for this version, or the project " +
                "may need reimporting before the IDE picks them up."
        }
    }

    private fun confirm(actionLabel: String, library: String, timeoutSeconds: Int): Boolean {
        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice = Messages.showDialog(
                project,
                "The AI wants to download and attach the sources for:\n\n$library\n\n" +
                    "Action: $actionLabel\nWaits up to ${timeoutSeconds}s\n\n" +
                    "This downloads over the network and changes your project's library " +
                    "configuration. It is only needed so the AI can read comments and Javadoc that " +
                    "decompiled bytecode does not carry.",
                "Download Library Sources?",
                arrayOf("Download", "Don't Download"),
                1,
                Messages.getQuestionIcon(),
            )
        }
        return choice == 0
    }

    private fun call(target: Any, name: String, arity: Int, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == arity }
            ?: throw NoSuchMethodException("$name/$arity on ${target.javaClass.name}")
        method.isAccessible = true
        return method.invoke(target, *args)
    }
}
