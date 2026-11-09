# independent-intelij-ai-plugin

![Build](https://github.com/piotr-szybicki/independent-intelij-ai-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Checking and repairing edited files

Enable `analyse_and_fix` in the chat's tools to let the assistant check a file after editing.
It runs fresh IDE analysis for errors and warnings. If there are problems, a separate repair chat
uses the calling chat's model and configuration, receives the entire file and diagnostics, and
returns a complete replacement. Analysis and repair repeat until the file is clean. The repair
chat is saved under its original chat in history, and edits use the normal change review flow.

The original assistant receives only `No problems Found` or `Problems were detected and fixed`.
Cancellation, failed analysis, incomplete model output, concurrent edits, or repairs that make no
progress return an error instead of claiming success. Files are never clipped to fit the model's
context window. Existing `get_file_problems` selections map to `analyse_and_fix`;
`apply_quick_fix` is no longer available.

## Packaging it and installation

`buildPlugin` produces the installable distribution ZIP:

```bash
./gradlew buildPlugin
```

The archive lands in `build/distributions/`, named from `rootProject.name` in
[settings.gradle.kts](./settings.gradle.kts) and `version` in [gradle.properties](./gradle.properties)
— currently:

```
build/distributions/IntelliJ Platform Plugin Template-0.0.1.zip
```

Install that file into a real IDE with <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
<kbd>Install Plugin from Disk…</kbd>, then restart. Remember the IDE only picks up
`INTELIJ_AI_SETTINGS`, so set them as user environment
variables rather than in a shell — an IDE launched from a desktop shortcut or Toolbox will not see a
variable exported in your shell profile.

### Running it

`runIde` starts a separate sandbox IDE with the plugin installed. It does not touch your day-to-day
IDE or its settings.

```bash
$env:INTELIJ_AI_SETTINGS = "$HOME/.config/independent-ai-plugin-settings.json"
./gradlew runIde

```
### Run db to log all the requests

```bash
docker run -d --name ai-usage -e MYSQL_ROOT_PASSWORD=secret -p 3306:3306 mysql:9
```

### Web search with Tavily

Add `"tavily": { "token": "your-tavily-api-key" }` to the top-level JSON configuration, then enable `web_serch` in the plugin's tool settings or conversation tools. The token can alternatively reference an environment variable, for example `"$TAVILY_API_KEY"`. Search queries are sent to Tavily and may consume Tavily credits.

### MCP servers

Add an `mcpServers` object alongside `configurations` in the JSON file named by
`INTELIJ_AI_SETTINGS`. For example, GitHub's remote server can use a personal access token:

```json
"mcpServers": {
  "github": {
    "url": "https://api.githubcopilot.com/mcp/",
    "headers": { "Authorization": "Bearer ${env:GITHUB_PAT}" }
  }
}
```

Set `GITHUB_PAT` in the IDE's environment before starting it. Keep the surrounding JSON object
and its existing `configurations` section; the example is one property, not a complete file.
The chat's tool selector lists connected MCP tools. Changes to the configuration are picked up
when tools are next requested. Connection and configuration errors are recorded in the IDE log.
MCP tools start disabled unless named in `conversation-defaults.mcp-tools`. Changes in the chat
tool selector are remembered as the project's MCP defaults until the project closes; chats with
their own saved selections keep those selections. Local servers can instead specify `command`,
`args`, and `env`.

An agent can choose MCP tools separately from its built-in `tools` policy. Add an entry to
the top-level `agents` array, for example:

```json
{
  "name": "github-agent",
  "description": "Works with GitHub repositories, issues, and pull requests.",
  "prompt": "Help with GitHub repositories, issues, and pull requests. Review changes and ask before consequential actions.",
  "mcp-tools": ["github/*"]
}
```

`github/*` selects every tool offered by the server named `github`; `*` selects all MCP servers,
and a qualified tool name selects one tool. An empty array selects none. Omitting `mcp-tools`
inherits the normal tool policy. The chat's tool selector can override the agent's selection.

When the usage database is enabled, actual Tavily requests are recorded in `tavily_requests` with the query, conversation and tool identifiers, HTTP status, duration, error type, and response body in a JSON column (up to one million characters). Non-JSON or truncated responses are stored as JSON strings. The API token is not recorded. Agent tool calls are also recorded in `model_tool_calls`. Recorded requests do not report billed credits or cost.
