<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# independent-intelij-ai-plugin Changelog

## [Unreleased]
### Added
- Prompt caching: the tool definitions and system prompt are marked as a cacheable prefix, and the tail of the conversation is marked as it grows, so a turn that runs several tool-call iterations stops re-reading the whole request each time. Token usage, including cache reads and writes, is logged per request
- Skills: directories listed under Settings | Tools | Anthropic Chat are scanned for `SKILL.md` files, and each skill's `name` and `description` are added to the system prompt so the model knows what is available without paying for the instructions until it uses one. Defaults to `.claude/skills`, `.skills`, and `~/.claude/skills`; a path outside the project is allowed
- MCP support: servers configured under Settings | Tools | Anthropic Chat have their tools offered to the model alongside the built-in ones, over stdio (local process) or Streamable HTTP (remote). Each call is shown for approval, as shell commands are
- Chat history: conversations are saved per project, reopened from the tool window's history button, and the last one is restored when the IDE restarts
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
