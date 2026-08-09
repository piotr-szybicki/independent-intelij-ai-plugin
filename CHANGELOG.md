<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# independent-intelij-ai-plugin Changelog

## [Unreleased]
### Added
- MCP support: servers configured under Settings | Tools | Anthropic Chat have their tools offered to the model alongside the built-in ones, over stdio (local process) or Streamable HTTP (remote). Each call is shown for approval, as shell commands are
- Chat history: conversations are saved per project, reopened from the tool window's history button, and the last one is restored when the IDE restarts
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
