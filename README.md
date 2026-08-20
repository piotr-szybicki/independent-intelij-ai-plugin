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
- Optionally, **`INTELIJ_AI_SETTINGS`** to keep the configuration file somewhere other than the
  project root — see [Keeping the file outside the project](#keeping-the-file-outside-the-project).

Which provider a request goes to is read from **`independent-ai-plugin-settings.json`** in the
project root, written with three example entries the first time a project is opened:

```json
{
  "usage-database": {
    "url": "jdbc:mysql://localhost:3306/ai_usage?user=root&password=secret",
    "enabled": true
  },
  "find-in-files": {
    "blocked-phrases": ["public", "import", "TODO", "comment_id"]
  },
  "summarizer": {
    "configuration": "Azure Foundry",
    "model": "gpt-5-nano",
    "max-tokens": 1500,
    "min-input-tokens": 0,
    "thinking": "off",
    "prompt": ""
  },
  "agents": [
    {
      "name": "coding-agent",
      "tools": [
        "list_directory", "read_project_file", "read_library_class", "get_file_structure",
        "find_in_files", "find_by_name", "find_usages", "find_implementations", "get_symbol_info",
        "create_file", "edit_file_lines", "move_file", "delete_file",
        "rename_symbol", "safe_delete", "apply_quick_fix",
        "get_file_problems", "git_status", "git_diff", "run_shell_command", "summarize"
      ]
    },
    {
      "name": "review-agent",
      "tools": [
        "list_directory", "read_project_file", "read_library_class", "get_file_structure",
        "find_in_files", "find_by_name", "find_usages", "find_implementations", "get_symbol_info",
        "get_comment", "get_file_problems",
        "git_status", "git_diff", "git_log", "git_blame"
      ]
    }
  ],
  "configurations": [
    {
      "name": "Azure Foundry",
      "model": "gpt-5.2-codex",
      "models": ["gpt-5.6-luna", "gpt-5.1-codex", "gpt-5.2-codex", "gpt-5.3-codex", "gpt-5-nano"],
      "url": "https://foundry-pszybicki.services.ai.azure.com/openai/v1/responses",
      "token": "TOKEN",
      "header-type": "Authorization",
      "protocol": "openai-responses",
      "thinking": "on",
      "effort": "medium",
      "max-tokens": 8000,
      "context-window": 200000,
      "request-timeout-seconds": 1000,
      "additional-customizations": {
        "anthropic-version": "",
        "extra-headers": {}
      }
    }
  ]
}
```

A `token` starting with `$` names an environment variable; anything else is used as the token
itself, which the file being plain text and usually in version control is the argument against.

### Keeping the file outside the project

Set **`INTELIJ_AI_SETTINGS`** to a path and that file is read instead of the one in the project root
— one set of providers for every project on the machine, kept out of version control:

```bash
setx INTELIJ_AI_SETTINGS "%USERPROFILE%\.config\independent-ai-plugin-settings.json"
```

A directory is taken as the directory the file is in, so `~/.config` and
`~/.config/independent-ai-plugin-settings.json` mean the same thing. `~` is your home directory, and
a relative path is resolved against whatever the IDE was started in, so make it absolute.

While the variable is set the file is **only read**:

- No starter file is written, at that path or in the project root.
- **Fill In Defaults** refuses — the file is yours to edit, and it may be shared by every project on
  the machine. **Edit File** still opens it.
- If nothing is at that path, the plugin does not start: the tool window opens on the error instead
  of a chat, an error balloon says the same at startup, and the side-chat button is hidden. It does
  **not** fall back to the project file or to the built-in default, because a variable naming a file
  is a statement about which providers to use, and quietly using different ones would be worse than
  saying so. Create the file, or unset the variable and restart the IDE.

The variable is read on every use, but the tool window is built once per project, so a change to it
takes effect on the next IDE start.

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
| `request-timeout-seconds` | how long one request may take before the turn fails with "request timed out"; raise it for slow reasoning models or a local server | `60` |
| `additional-customizations.anthropic-version` | sent only on the Messages API; empty omits the header | `2023-06-01` there, nothing elsewhere |
| `additional-customizations.extra-headers` | routing or tenancy headers for a gateway | none |

The same file's **`find-in-files`** section lists the searches `find_in_files` refuses outright.
Every project has a handful of words that appear in every file it has, and asking where `public` is
returns a listing the length of the project, learns nothing from it, and leaves the whole thing in
the conversation to be re-sent on every turn afterwards. Nothing is blocked until you say so.

```json
"find-in-files": {
  "blocked-phrases": ["public", "import", "TODO"]
}
```

A phrase blocks a search when it is the **whole** query, ignoring case and surrounding space —
blocking `get` does not block `getUserConfiguration`, because a longer query containing a blocked
word is the narrower search that was being asked for. The model is told which phrase stopped it, so
it asks a better question rather than retrying the same one in a different case. Edits take effect
on the next search; there is no restart.

`find_in_files` answers with locations only — the file's path, then the line number of each match
below it — and stops after **100 files**. It used to echo each matching line, which meant the query
came back once per hit with up to two hundred characters of context around it, permanently, in every
later request of that conversation.

### Paying a cheap model to read the long output

The **`summarize`** tool runs another tool and hands back a summary of what it returned, written by a
second model that can be a much cheaper one. It takes the tool's name in `tool`, that tool's own
arguments in `input`, and — worth writing every time — a `focus` saying what the summary has to
answer:

```json
{
  "tool": "run_shell_command",
  "input": {"command": "./gradlew test"},
  "focus": "which tests failed, with the assertion message and the file each is in"
}
```

The point is what a tool call costs *after* it returns. Its output is a message in the conversation
from then on, re-sent in full with every later request of that chat, so a test run that came back
twelve thousand tokens long is paid for on every turn that follows. Summarising it once, on a model
priced a fraction of the one doing the work, replaces those twelve thousand with a few hundred for
the rest of the conversation.

What the summary leaves out is gone — the original is **not** kept for the model, and it is told so,
so it can run the call again, narrowed, when it needs the exact text. That is the trade, and it is
why the description tells the model not to reach for this when it needs something word for word,
such as a file it is about to edit. **You** still see the whole output: the tool card in the
transcript holds what the inner tool returned, and a shell command is in the Terminal tab as usual.

The **`summarizer`** section of the configuration file says who writes them:

| Field | Values | Default |
| --- | --- | --- |
| `configuration` | which entry in `configurations` to send the summarising request to | the provider the chat itself is on |
| `model` | which model to ask that provider for; it does **not** have to be in that entry's `models`, since nothing chooses it from the dropdown | that entry's own `model` |
| `max-tokens` | the cap on the summary itself | `1500` |
| `min-input-tokens` | output shorter than this comes back as it is, unsummarised — there is nothing to save and a round trip to lose | `400` |
| `thinking` | `off`, `on`, `provider-default`, exactly as in a `configurations` entry | `off` |
| `prompt` | added after the built-in instructions, for a standing rule of your own | none |

`thinking` defaults to **`off`** and is sent as such rather than left out: `"thinking":
{"type": "disabled"}` on the Messages API, `"reasoning": {"effort": "none"}` on Responses.
Compressing text that is already in front of the model is not what a reasoning budget is for, and a
reasoning model left on its provider's default spends more thinking about the summary than the
summary saves. When it is `off` no `effort` is sent either; set it to `on` and the request carries
the `effort` of the entry it is going to. A model whose API refuses to be told `none` — some of the
GPT-5 family take `minimal` as their floor — needs `provider-default` here, or `on` with a `low`
effort on that entry.

`summarize` is **off** until you select it under <kbd>Settings</kbd> > <kbd>Tools</kbd> >
<kbd>AICodingAgent</kbd>, like the other tools that spend money or change things. The request is recorded in the usage
database against the same conversation as the rest of the chat, so its cost shows up in
`model_requests` under the summariser's model name; the chat's own token meter counts only what the
main model sent and received.

If the summariser cannot be reached, names a configuration that is not in the file, or comes back
empty, the tool's own output is returned in full with a line saying which of those happened. A
failure to compress is not a reason to lose what the tool already did — though the full output may
then be over **Tokens per tool result** and be withheld, which is the same offer to approve or edit
as any other oversized call.

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

The database and its two tables are created if they are not there, so the URL may name a database
that does not exist yet. **Test Connection** under <kbd>Settings</kbd> > <kbd>Tools</kbd> >
<kbd>AICodingAgent</kbd> > <kbd>Logging</kbd> connects once against the file as it stands, sets the
schema up and reports the server version, the row count and the cost recorded so far.

`model_requests` holds one row per request. `model_tool_calls` holds one row per tool call, with a
foreign key back to the `request_id` of the response that asked for it — the tool's name, its
arguments, what it returned, how it ended, how long it took, and an estimate of what its arguments
and its output cost in tokens. One response can ask for several tools at once, which is why they are
a table rather than more columns, and it is what makes `GROUP BY tool_name` the answer to which tool
is slowest, which one fails most and whose output is eating the context window.

```sql
SELECT tool_name, COUNT(*) calls, AVG(duration_ms) ms, SUM(result_tokens) tokens
FROM model_tool_calls GROUP BY tool_name ORDER BY tokens DESC;
```

Pick the provider and the model from the two dropdowns above the chat transcript. **Both belong to
that conversation**: they are saved with it, a chat reopened from the history comes back on what it
was sent to, and a second chat can be on something else. Changing either takes effect from the next
message, with the conversation so far carried over, so a chat can start cheap and move up without
being restarted — and it becomes the default the next new chat starts on.

<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>AICodingAgent</kbd> sets that default and says what a
selection will send and what stops it if anything does. **Fill In Defaults** there rewrites the file with every optional field written out at the value
it is already running on — which is how a file written before a field existed gets that field back.
Only **Tool calls per message** and **Tokens per tool result** stay on that page — they guard the
agent loop rather than describing the model — along with tools, MCP servers and skills.

**Tokens per tool result** is the cap on what one tool call may hand back, counted with
[JTokkit](https://github.com/knuddelsgmbh/jtokkit), the JVM port of OpenAI's tiktoken. Go over it —
500 tokens by default — and the output is *not* sent: the model gets a note saying it was withheld.
The chat still shows the output in full, and your next message carries on as normal. It is set low
because tool output is the expensive kind: what a call returns is re-sent with every later request in
the conversation, so one search that matched half the project is paid for over and over. Set it to
`0` to turn the check off.

**Every tool the model asked for still runs.** A single response can ask for six at once, and going
over the limit is one call's problem — the other five are answered as normal. The turn stops once
that round is finished, so what to do about the withheld output is yours to decide.

Each oversized call gets an **Approve output** button on its own card, with the token count in the
tooltip. Press it and the full output is sent as *that tool call's* result — not as a message from
you — so the model reads it as what the tool returned. Beside it, **Edit** opens the output from
**`.cache/`** in the project root: cut it down to the part that actually mattered and Approve then
sends your version instead. Unsaved edits count — the editor's copy is what gets sent, and is saved
to disk on the way.

Then press **Continue**, next to **Export MD** under the reply, to send the conversation on. Anything
you left unapproved keeps its withheld note, which is a perfectly good answer: the note asks the
model to narrow that call rather than repeat it. Both offers are withdrawn the moment the
conversation is sent, and are not restored when a chat is reopened from the history — by then the
note has gone to the provider and replacing what it stands for would be rewriting something already
sent. The conversation is never stuck either way. Files in `.cache/` are never deleted by the plugin;
empty the folder whenever you like.

### Tools and skills for one conversation

The tools on the settings page are the default, not the last word. The **Tools** button beside the
provider dropdowns opens the same three lists — tools, MCP tools, skills — **for the chat you are in
and nothing else**. Tick *Use a tool set chosen for this chat only* and that chat calls exactly what
you left ticked, whatever the settings page says and whatever the agent's own list says; leave it
clear and the chat inherits as before. The choice is saved with the chat, so one reopened from the
history comes back with it, and a new chat starts inheriting again. The button says which it is on.

**Skills are off in every new chat.** Nothing in the skill directories is described to the model
until that chat is told to use one, so the standing cost of a skill you have written but do not want
today is zero. There are two ways to switch one on:

- The **Skills** tab of that same dialog, which makes it *available*: its name and description ride
  along with each message, and the assistant reads the `SKILL.md` if it decides the skill applies.
- Typing **`/`** as the first character of a message, which makes it *available and used*. A
  dropdown of skills opens at the caret, <kbd>Enter</kbd> picks one and <kbd>Escape</kbd> leaves the
  `/` alone. The skill stays on for the rest of the chat, and the message you are writing carries a
  block telling the model to read that file and follow it for this request — the difference between
  "you may use this" and "use this". A `/` anywhere but the start of a message is left alone, so
  paths and `and/or` are safe to type.

The settings page keeps deciding which skills *exist* to be picked — directories to scan, and
**Select Skills…** to narrow the list a chat may choose from — but nothing there is sent on its own.

#### What a new chat starts with

The IDE settings page is one machine's answer; a project can give its own in the
**`conversation-defaults`** section of `independent-ai-plugin-settings.json`, which travels with the
project the way the providers and the agents already do.

```json
"conversation-defaults": {
  "tools": ["read_project_file", "get_file_structure", "find_in_files"],
  "mcp-tools": ["mcp__github__search_issues"],
  "skills": ["write-tests"]
}
```

Each array is what a **new** chat opens with. `tools` and `mcp-tools` replace what the settings page
has switched on; `skills` is switched on outright, the one way a chat starts with skills without
anyone typing `/`. **A missing array is not an empty one**: leave `tools` out and the settings page
decides, write `"tools": []` and chats in this project start with no built-in tools at all. Leaving
`mcp-tools` out is how you keep whatever the settings page allows, which is *every* MCP tool unless
you have narrowed it there.

This is the bottom layer, and everything above it still applies: an agent's own `tools` and `skills`
replace it in the chats that agent starts, and the **Tools** button replaces it for one chat. **Fill
In Defaults** writes the section out at what the IDE is already running on, so a file written before
the section existed gets it filled in rather than guessed at.

### Handing work to an agent

Work out **what** to build in the main chat, then hand the building over. Type **`@`** anywhere in
the message box and a dropdown of the available agents opens at the caret; keep typing to filter it,
<kbd>Enter</kbd> picks one and <kbd>Escape</kbd> leaves the `@` where it is.

Picking one drops an **agent hand-off** card into the transcript and writes the last reply — the same
Markdown **Export MD** would have saved — to **`.cache/<agent>-spec-<stamp>.md`**, which opens in the
editor. That file is the brief, and the whole of it is what the agent is started with, so cut, add
and rewrite until it says what you mean. **Open spec** brings it back if you closed it. Nothing has
been sent yet.

**Proceed** reads the file as it stands in the editor — unsaved edits count — and opens a **new
chat** running that agent, with the spec as its opening message and the agent's own instructions in
place of the ordinary ones. The card settles to **Handed off** with an **Open agent chat** link, and
the agent chat carries a banner naming the agent, with **Spec** and **Back to the chat that started
it**. In the chat history the agent's chats are listed with **`@name`** in front of the title, so a
hand-off is never mistaken for an ordinary conversation. **Cancel** drops the hand-off and leaves the
spec file on disk.

The way back is the mirror of the way out. Under any reply in the agent chat, **Return summary**
writes that reply to **`.cache/<agent>-summary-<stamp>.md`** and opens it — trim it to what the other
chat actually needs — and hands the file to the chat that started the agent. The button settles to
*Returned*.

That chat picks it up in a **Returned by agents** section above the message box, its own section
above your attachments: one chip per summary, click it to open the file, ✕ to drop it. It goes with
your **next message**, read from the file as it stands at that moment, so edits made after returning
still count. Send, and the chip clears like an attachment does. The section survives the chat being
closed and reopened, which is the point — the agent usually finishes while you are looking at
something else.

**No agent comes built in.** Nothing in the plugin's code defines one: an agent is either an entry
in the `agents` section of `independent-ai-plugin-settings.json` or an `AGENT.md` on disk, both of
them yours to write and change without rebuilding anything. Type `@` in an empty project and the
dropdown says so rather than offering something you never asked for.

The two worth starting from, and the two this repository's own settings file carries, are
**`coding-agent`** — implements the spec and reports what it changed and what it verified — and
**`review-agent`**, which judges the code against the spec and is given reading and navigation tools
only, so there is nothing for it to edit with. Both are written out in full in the section below;
copy them into your own file and edit from there.

#### What an agent may call

**An agent's tools are its own, not the chat's.** The tools on the settings page are what *you* get
in the main chat; an agent that names its tools gets exactly those, whether or not you have them
switched on. That is deliberate: `@coding-agent` can create, edit, move, delete and rename from the
first message, in a chat you started from a specification you approved, without leaving those tools
armed in every conversation you have. An agent that names no tools inherits the settings page as
before. The banner at the top of an agent chat lists what that chat can call.

The **`agents`** section of `independent-ai-plugin-settings.json` is where an agent is defined. It is
the roster: one entry per agent, `name` and `prompt` being the whole of what makes one, plus two
arrays — `tools`, which decides what it may call, and `skills`, which decides what its chats start
with switched on.

```json
"agents": [
  {
    "name": "coding-agent",
    "description": "Implements an agreed specification: writes the code, runs it, reports what it did.",
    "prompt": "You are a coding agent. The message you were started with is a specification...",
    "spec-template": "# What to build\n\nDescribe the change here.\n\n# Constraints\n\n# Done when",
    "tools": ["read_project_file", "get_file_structure", "find_in_files",
              "edit_file_lines", "create_file", "get_file_problems", "run_shell_command"],
    "skills": ["write-tests"],
    "model": "claude-opus-5"
  },
  {
    "name": "migrator",
    "description": "Moves calls from the old API to the new one.",
    "prompt": "You migrate call sites, one file at a time...",
    "tools": ["*", "-run_shell_command", "-delete_file"],
    "skills": ["explain-code"]
  }
]
```

`prompt` is the agent's whole brief and `description` is the line the `@` dropdown shows.
`spec-template` is what its spec file starts from when there is no reply to draft one out of.

`skills` is the one exception to skills being off by default, and it is the same exception `tools`
already is: an agent chat is started from a specification you approved, for an agent you wrote, so
the procedure it is meant to follow is switched on from its first message instead of waiting for you
to type `/`. Names come from the skill directories. `"skills": []` or no `skills` at all means the
agent's chats start with none, like every other chat.

An entry naming an agent an `AGENT.md` already defines overrides it, field by field: `tools` replaces
its tool list, `skills` its starting skills, and `description`, `prompt`, `spec-template`,
`configuration` and `model` replace theirs when they are not empty. Left-out fields keep whatever the
`AGENT.md` said, so overriding just the tools is two lines. `"tools": []` or no `tools` at all means
*inherit the settings page*, `["*"]` means every tool there is, and a `-name` entry takes one away.

**Fill In Defaults** on the settings page writes the section out at the values it is already running
on — the quickest way to see every agent's real tool list and start editing from it. Prompts and spec
templates that live in an `AGENT.md` are not copied into the JSON; only what you wrote there stays
there.

Or define one as **`.agents/<name>/AGENT.md`** in the project (`.claude/agents/*.md` and
`~/.claude/agents/*.md` are read too), which keeps a long prompt out of JSON string escapes.
Everything below the frontmatter is the agent's system prompt:

```markdown
---
name: test-writer
description: Writes the tests for a change that is already made.
tools: read_project_file, get_file_structure, find_in_files, create_file, edit_file_lines
skills: write-tests
model: claude-sonnet-4-5
---

You write tests, and nothing else...
```

`tools` is the tools that agent may call — a list to allow exactly those, `*` for every tool there
is, or `-name` entries to take a few away. Leave it out and the agent inherits the settings page.
`skills` is the skills its chats start with switched on, comma-separated; leave it out and they
start with none, as every other chat does. `configuration` and `model` name the provider and model
its chats open on, and `spec_template` is what its spec file starts from when there is no reply to
draft one out of. The `agents` section of the settings file overrides any of this.

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



#####################################################