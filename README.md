# independent-intelij-ai-plugin

![Build](https://github.com/piotr-szybicki/independent-intelij-ai-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

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
<kbd>Install Plugin from Disk…</kbd>, then restart. Remember the IDE only picks up `AI_API_KEY` and
`AI_API_URL` at startup, so set them as user environment variables rather than in a shell — an IDE
launched from a desktop shortcut or Toolbox will not see a variable exported in your shell profile.
## Development

### Prerequisites

- **JDK 25** — the 2026.2 platform targets it, and Gradle builds with whatever JVM it runs on.
- **IntelliJ IDEA 2026.2.x** to install into. The plugin is built against `2026.2.1`; it also
  depends on the bundled Terminal plugin and the VCS platform module, both of which ship with IDEA.
- An API key for your provider in the **`AI_API_KEY`** environment variable, which is what the
  starter configuration file's Anthropic entry points its token at. Each entry names its own
  variable, so a file with three providers in it can use three.
- Optionally, an endpoint in **`AI_API_URL`**. It says where requests go only when there is no usable
  configuration file — it does **not** override the `url` of a configuration you have selected.
  Replacing one entry's URL would leave that entry's protocol, token header and token describing a
  provider the URL no longer points at, which is a request that cannot be sent rather than an
  override. Switching endpoint is the dropdown.

Which provider a request goes to is read from **`independent-ai-plugin-settings.json`** in the
project root, written with three example entries the first time a project is opened:

```json
{
  "usage-database": {
    "url": "",
    "enabled": true
  },
  "configurations": [
    {
      "name": "Anthropic Claude",
      "model": "claude-sonnet-5",
      "models": ["claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001"],
      "url": "https://api.anthropic.com/v1/messages",
      "token": "$AI_API_KEY",
      "header-type": "x-api-key",
      "protocol": "anthropic-messages",
      "thinking": "on",
      "effort": "medium",
      "max-tokens": 8000,
      "context-window": 200000,
      "additional-customizations": {
        "anthropic-version": "2023-06-01",
        "extra-headers": {}
      }
    }
  ]
}
```

A `token` starting with `$` names an environment variable; anything else is used as the token
itself, which the file being plain text and usually in version control is the argument against.

Everything after `token` is optional and has a default:

| Field | Values | Default |
| --- | --- | --- |
| `models` | every model this provider can be asked for, offered in the chat window's model dropdown | just `model` |
| `header-type` | `x-api-key`, `Authorization`, `api-key` | read off the URL |
| `protocol` | `anthropic-messages`, `openai-responses`, `openai-chat-completions` | read off the URL |
| `thinking` | `on`, `off`, `provider-default` (or a JSON boolean) | `on`, or `provider-default` on Chat Completions, which cannot carry it |
| `effort` | `low`, `medium`, `high`, `xhigh`, `max`, `provider-default` | `medium` |
| `max-tokens` | the reply cap, thinking included | `8000` |
| `context-window` | what compaction measures against; `0` switches it off | `200000` |
| `additional-customizations.anthropic-version` | sent only on the Messages API; empty omits the header | `2023-06-01` there, nothing elsewhere |
| `additional-customizations.extra-headers` | routing or tenancy headers for a gateway | none |

The same file's **`usage-database`** section says where one row per request is recorded — a MySQL
JDBC URL in `url`, and `enabled` to stop the writing without losing it. An empty URL, or no section
at all, records nothing. Write `${env:NAME}` for the password: the file is plain text and usually in
version control.

```json
"usage-database": {
  "url": "jdbc:mysql://localhost:3306/ai_usage?user=root&password=${env:MYSQL_PASSWORD}",
  "enabled": true
}
```

The database and its `model_requests` table are created if they are not there, so the URL may name a
database that does not exist yet. **Test Connection** under <kbd>Settings</kbd> > <kbd>Tools</kbd> >
<kbd>AICodingAgent</kbd> > <kbd>Logging</kbd> connects once against the file as it stands, sets the
schema up and reports the server version, the row count and the cost recorded so far.

Pick the provider and the model from the two dropdowns above the chat transcript. **Both belong to
that conversation**: they are saved with it, a chat reopened from the history comes back on what it
was sent to, and a second chat can be on something else. Changing either takes effect from the next
message, with the conversation so far carried over, so a chat can start cheap and move up without
being restarted — and it becomes the default the next new chat starts on.

<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>AICodingAgent</kbd> sets that default and says what a
selection will send and what stops it if anything does. **Fill In Defaults** there rewrites the file with every optional field written out at the value
it is already running on — which is how a file written before a field existed gets that field back.
Only **Tool calls per message** stays on that page — it guards the agent loop rather than
describing the model — along with tools, MCP servers and skills.

### Running it

`runIde` starts a separate sandbox IDE with the plugin installed. It does not touch your day-to-day
IDE or its settings.

```bash
./gradlew runIde
```

The sandbox inherits Gradle's environment, so set the key in the **same shell** before launching, or
the chat will start with no credentials:

```bash
$env:AI_API_KEY = "sk-ant-..."; ./gradlew runIde
```

The bash equivalent is `AI_API_KEY=sk-ant-... ./gradlew runIde`. Any variable a configuration's
`token` names rides along the same way. `AI_API_URL` also rides along, but only matters in a sandbox
whose project has no configuration file — clear it from that shell if a configuration is meant to
decide the endpoint.

First run downloads the target IDE (~1 GB) and takes a while; later runs reuse it.

### Other tasks

| Task | What it does |
| --- | --- |
| `./gradlew compileKotlin` | Fastest check that the sources build |
| `./gradlew test` | Runs the test suite |
| `./gradlew verifyPlugin` | Checks the plugin against IntelliJ Platform compatibility rules |

`signPlugin` and `publishPlugin` come from the Gradle plugin but are **not configured** in this
project — publishing to JetBrains Marketplace needs signing certificates and a deployment token added
to [build.gradle.kts](./build.gradle.kts) first.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

---
### Run db to log all the requests
docker run -d --name ai-usage -e MYSQL_ROOT_PASSWORD=secret -p 3306:3306 mysql:9,