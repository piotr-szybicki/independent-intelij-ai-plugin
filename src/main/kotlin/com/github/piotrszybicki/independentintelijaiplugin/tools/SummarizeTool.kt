package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentClient
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ReasoningOptions
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.TokenCounter
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import com.github.piotrszybicki.independentintelijaiplugin.settings.Effort
import com.github.piotrszybicki.independentintelijaiplugin.settings.SummarizerConfig
import com.github.piotrszybicki.independentintelijaiplugin.settings.ThinkingMode
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class SummarizeTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_INPUT_CHARS = 400_000
        private const val HEAD_CHARS = 260_000

        private val OWN_FIELDS = setOf("tool", "input", "focus")

        private val prettyJson = GsonBuilder().setPrettyPrinting().create()

        private val SYSTEM_PROMPT = """
            You compress the output of a developer tool. What you write is all another model will
            see of it: the original is not passed on, and it cannot ask you for the rest.

            Keep every fact it would need to act on -- identifiers, file paths, line numbers, exit
            codes, counts, versions, and error or assertion messages -- and quote those exactly
            rather than describing them. Say how each failure differs from the others.

            Drop what carries nothing: progress and download chatter, timings, banners, stack frames
            inside libraries and frameworks, and repeats of one failure -- give the number instead.

            Never guess at what the output does not say and never propose a fix. If there is nothing
            worth keeping, say so in one line. Plain prose and lists, no preamble.
        """.trimIndent()
    }

    override val name = "summarize"

    override val description =
        "Runs another tool and hands back a summary of what it returned instead of the whole " +
            "thing, written by a second, cheaper model. Name the tool in \"tool\" and give it its " +
            "own arguments in \"input\", exactly as you would when calling it directly. Reach for " +
            "this when the call is worth making but its output is long and only its gist matters " +
            "-- a build or test run, a wide search, a log. Do not use it for anything you need " +
            "word for word, such as code you are about to edit: the summary is all you get back, " +
            "and the original is not kept for you. The user still sees the full output in the IDE."

    override val interruptible = false

    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("tool", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Name of the tool to run, such as run_shell_command. It must be one of the " +
                        "tools you have, and cannot be summarize itself.",
                )
            })
            add("input", JsonObject().apply {
                addProperty("type", "object")
                addProperty(
                    "description",
                    "The arguments for that tool: the object you would have sent it directly.",
                )
            })
            add("focus", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "What the summary has to answer, in a sentence -- \"which tests failed and " +
                        "why\", \"the compiler errors and the files they are in\". Everything " +
                        "else may be dropped, so say what you are after.",
                )
            })
        })
        add("required", JsonArray().apply { add("tool"); add("input") })
    }

    @Volatile
    private var toolbox: List<AICodingAgentTool> = emptyList()

    @Volatile
    private var conversationId: String = ""

    @Volatile
    private var chatConfiguration: String = ""

    fun bind(tools: List<AICodingAgentTool>, conversationId: String, chatConfiguration: String) {
        this.toolbox = tools.filter { it.name != name }
        this.conversationId = conversationId
        this.chatConfiguration = chatConfiguration
    }

    override fun execute(input: JsonObject): String {
        val toolName = input.get("tool")?.asString?.trim().orEmpty()
        if (toolName.isEmpty()) return "Error: missing 'tool' -- name the tool to run and summarize"
        if (toolName == name) return "Error: summarize cannot run itself"

        val delegate = toolbox.firstOrNull { it.name == toolName }
            ?: return "Error: there is no tool called '$toolName'. Tools you can summarize: " +
                toolbox.joinToString(", ") { it.name }

        val arguments = argumentsFor(input)
            ?: return "Error: 'input' must be an object -- the arguments you would have sent '$toolName'"
        val focus = input.get("focus")?.asString?.trim().orEmpty()

        val output = delegate.execute(arguments)
        val tokens = TokenCounter.count(output)

        val settings = settings()
        if (tokens < settings.minInputTokens) {
            return "$toolName ran, and its output came to $tokens tokens -- under the " +
                "${settings.minInputTokens}-token floor for summarising, so here it is as it is.\n\n$output"
        }

        val summary = try {
            summarize(settings, toolName, arguments, focus, output)
        } catch (e: Exception) {
            log.info("Summarising the output of '$toolName' failed: ${e.message}")
            return "$toolName ran, but the summariser (${settings.describe()}) could not be " +
                "reached: ${e.message}. Its output follows in full.\n\n$output"
        }

        if (summary.isBlank()) {
            return "$toolName ran, but the summariser (${settings.describe()}) came back with " +
                "nothing. Its output follows in full.\n\n$output"
        }

        return "$toolName ran. Its output, $tokens tokens of it, was summarised by " +
            "${settings.describe()} into ${TokenCounter.count(summary)}. What the summary leaves " +
            "out is gone -- run the tool again, narrowed, if you need the exact text.\n\n$summary"
    }

    private fun argumentsFor(input: JsonObject): JsonObject? {
        val given = input.get("input")?.takeIf { !it.isJsonNull }
            ?: return JsonObject().apply {
                input.entrySet().filterNot { it.key in OWN_FIELDS }.forEach { add(it.key, it.value) }
            }
        if (given.isJsonObject) return given.asJsonObject
        if (given.isJsonPrimitive) {
            return runCatching { JsonParser.parseString(given.asString) }.getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }
        return null
    }

    private fun settings(): SummarizerConfig {
        val loaded = AgentConfigurations.getInstance(project).summarizer()
        loaded.error?.let { log.warn("summarising on the defaults: $it") }
        return loaded.summarizer
    }

    private fun summarize(
        settings: SummarizerConfig,
        toolName: String,
        arguments: JsonObject,
        focus: String,
        output: String,
    ): String {
        val configuration = configurationFor(settings)
        val endpoint = AICodingAgentEndpoint.from(configuration)

        val request = buildString {
            appendLine("Tool: $toolName")
            appendLine("Its arguments: ${prettyJson.toJson(arguments)}")
            if (focus.isNotEmpty()) appendLine("What the summary has to answer: $focus")
            appendLine()
            appendLine("Its output follows.")
            appendLine("----------")
            append(clipped(output))
        }

        val system = listOf(SYSTEM_PROMPT, settings.prompt)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val turn = AICodingAgentClient.sendMessage(
            endpoint = endpoint,
            model = configuration.model,
            maxTokens = settings.maxTokens,
            messages = listOf(ChatMessage.text("user", request)),
            system = system,
            reasoning = reasoningFor(settings, configuration),
            conversationId = conversationId,
        )

        return turn.content
            .filter { it.isJsonObject && it.asJsonObject.get("type")?.asString == "text" }
            .joinToString("\n") { it.asJsonObject.get("text")?.asString.orEmpty() }
            .trim()
    }

    private fun reasoningFor(settings: SummarizerConfig, configuration: AgentConfiguration): ReasoningOptions =
        if (settings.thinking == ThinkingMode.OFF) {
            ReasoningOptions(Effort.PROVIDER_DEFAULT, ThinkingMode.OFF)
        } else {
            ReasoningOptions(configuration.effort, settings.thinking)
        }

    private fun configurationFor(settings: SummarizerConfig): AgentConfiguration {
        val configurations = AgentConfigurations.getInstance(project)
        val named = settings.configurationName
        val base = if (named.isBlank()) {
            configurations.resolve(chatConfiguration, "")
        } else {
            configurations.load().configurations.firstOrNull { it.name == named }
                ?: throw IllegalStateException(
                    "\"${SummarizerConfig.SECTION}\" names the configuration \"$named\", which is " +
                        "not in ${AgentConfiguration.FILE_NAME}",
                )
        }
        return if (settings.model.isBlank()) base else base.copy(model = settings.model)
    }

    private fun clipped(output: String): String {
        if (output.length <= MAX_INPUT_CHARS) return output
        val tail = MAX_INPUT_CHARS - HEAD_CHARS
        return output.take(HEAD_CHARS) +
            "\n\n[... ${output.length - MAX_INPUT_CHARS} characters from the middle were not sent " +
            "to the summariser ...]\n\n" +
            output.takeLast(tail)
    }

    private val log = Logger.getInstance(SummarizeTool::class.java)
}
