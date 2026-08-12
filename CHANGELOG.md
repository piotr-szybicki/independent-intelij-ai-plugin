<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# independent-intelij-ai-plugin Changelog

## [Unreleased]
### Fixed
- Stopping a turn no longer saves the conversation part-way through an iteration. A chat saved at that moment held tool calls with no results, which the API rejects outright, so reopening it produced a conversation that could not be continued at all. Such a history is now also repaired when sent, so chats already saved in that state work again

### Changed
- Tool descriptions are about 40% shorter. They are re-sent on every request, so the wording was costing roughly 1,400 tokens a turn to say things the model did not need told twice

### Added
- Git tools: a **Version control** group holding `git_status`, `git_diff`, `git_log` and `git_blame`. They go through the IDE's own Git integration rather than the terminal, so they need no approval dialog, return an exit code and output as data instead of scraped terminal text, and — for `git_status` — read the same change list the Local Changes view shows, so the model and the user see one answer. Off by default, like every other non-reading group. Read-only: nothing here commits, checks out or touches a remote
- Tool selection: Settings | Tools | AICodingAgent has a **Select Tools** button opening a picker that lists the built-in tools by category, with a tick-the-whole-group box on each and Read-Only Defaults / Select All / Clear presets. Only the ticked tools are sent. A tool that is off cannot be called, so this bounds what the model may do as well as what the request costs. Defaults to reading and navigation only — editing, running and debugging are switched on when wanted. **Existing setups get the new default**, so re-tick what you need after upgrading
- Prompt caching: the tool definitions and system prompt are marked as a cacheable prefix, and the tail of the conversation is marked as it grows, so a turn that runs several tool-call iterations stops re-reading the whole request each time. Token usage, including cache reads and writes, is logged per request
- Skills: directories listed under Settings | Tools | AICodingAgent are scanned for `SKILL.md` files, and each skill's `name` and `description` are added to the system prompt so the model knows what is available without paying for the instructions until it uses one. Defaults to `.claude/skills`, `.skills`, and `~/.claude/skills`; a path outside the project is allowed
- MCP support: servers configured under Settings | Tools | AICodingAgent have their tools offered to the model alongside the built-in ones, over stdio (local process) or Streamable HTTP (remote). Each call is shown for approval, as shell commands are
- Chat history: conversations are saved per project, reopened from the tool window's history button, and the last one is restored when the IDE restarts
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
