# Mermaid diagrams in chat: local image rendering

Revision: 1  
Status: Proposed; not implemented

## Goal

Render Mermaid diagrams embedded in assistant Markdown responses as inline PNG images in the chat, while retaining the original Markdown for export and conversation history. Rendering must be local, work offline after installation, and preserve existing chat behavior.

## Scope and user experience

- Recognize CommonMark fenced code blocks whose first info-string token is `mermaid`, case-insensitively. Support backtick and tilde fences, variable fence lengths, indentation and nesting according to the existing CommonMark parser. Do not interpret inline code, ordinary text, or a Mermaid-looking block inside another code block as a diagram.
- Show a loading indicator at the block's position while the image is being generated. Show the image between the surrounding content in the original order. Multiple diagrams and mixed tables, lists, text and shell blocks must work in one response.
- Preserve the original Mermaid source exactly in stored messages, copies of the full response, and Markdown export. Never replace it with a temporary image path or generated image data in exported Markdown.
- Keep existing shell copy and terminal-paste controls functional, as well as links, tables, text selection, response export and other response controls.
- Provide an accessible label identifying each diagram, and an action to view or copy its Mermaid source. Do not rely on color alone to communicate loading or errors.
- On failure, show a concise error and the original source in a readable code block, with a retry action. An invalid diagram must not prevent the rest of the message or other diagrams from rendering. If the embedded browser is unavailable, show the same source fallback and an explanation.

## Rendering architecture

- Parse Markdown into an AST with the current CommonMark extensions. Identify Mermaid nodes through their actual parsed node type and info string, not a regular expression over raw Markdown. Preserve the structure and order of surrounding content, including diagrams nested in lists or block quotes.
- Use the official Mermaid JavaScript renderer in a locally bundled, version-pinned distribution and render in an IntelliJ JCEF browser. Declare the required JCEF plugin dependency for the targeted IntelliJ platform. Confirm that the selected Mermaid version is compatible with the bundled Chromium version and include required licenses and notices.
- Use a reusable rendering service rather than creating a permanent browser per diagram. Serialize or otherwise safely coordinate requests, associate each result with its request, and limit concurrent work. Keep the browser and its callbacks under an IntelliJ `Disposable` tied to the relevant project or service lifetime.
- Generate SVG with Mermaid, then rasterize it locally to PNG in a browser canvas or another verified renderer. Ensure the chosen conversion supports Mermaid's generated SVG, fonts, and diagram types; do not assume every SVG feature will survive rasterization. Return image bytes and intrinsic dimensions to the UI asynchronously.
- Keep network requests disabled for diagram rendering. Do not use a CDN, external diagram API, remote fonts, or send diagram source outside the user's computer. Bundle all required scripts, assets and fonts or use local system fonts. Validate that nested Mermaid dependencies and supported diagram types work offline.
- Treat diagram source as untrusted. Pass it as data, never by concatenating it into executable JavaScript or HTML. Use Mermaid's strict security mode, prevent navigation and popups, restrict resource loading, and reject external resource references. Apply sensible source-size, edge-count, pixel-count and timeout limits. Do not weaken these controls through diagram-supplied configuration.
- Select a rendering theme from the active IDE light/dark theme. Cache by source, renderer version, theme and effective render scale. Invalidate or regenerate on theme change and renderer update. Use bounded in-memory caching; any disk cache must be bounded, local, and removed by the IDE's cache cleanup. Avoid including source in log messages.
- PNG is preferred to JPEG because it preserves text and lines. Render at an appropriate device scale for HiDPI screens; downscale to available width without changing aspect ratio. Do not enlarge small diagrams unnecessarily. Preserve transparency or use a theme-appropriate background. Large diagrams must remain legible through an explicit zoom/open action or horizontal scrolling, rather than being reduced to unreadable text.
- Avoid blocking the Swing event dispatch thread. Display updates occur on that thread; rendering and image decoding run asynchronously. Cancel or discard stale work when a message is removed, a conversation is cleared, the project closes, or a newer request supersedes it. Limit memory and release browser resources on disposal.

## Integration

- Integrate at the assistant response rendering boundary, currently `AssistantRow` and `ShellCommandBlocks`, without changing the Markdown retained by `AssistantRow.toMarkdown()` or `AiTurnRow` export. Refactor the renderer as needed so diagrams and HTML segments retain document order and nested context. Avoid splitting raw source in ways that change list, quote or fence semantics.
- Keep normal messages on the existing lightweight path. Only initialize the diagram service when a response actually contains a Mermaid block. Support loading historic messages and multiple response rows. During progressive or partial responses, render complete diagrams and avoid repeatedly rendering incomplete syntax; re-render when the completed source changes.
- A renderer failure, timeout, browser crash, unsupported JCEF environment, oversized diagram or resource limit must yield the source fallback and leave the rest of the chat usable. A retry must not create duplicate images or callbacks. Failures must not alter persisted conversation data.

## Verification and acceptance criteria

1. A flowchart and a sequence diagram in separate Mermaid fences render as images in the correct location, with text before, between and after them intact.
2. Multiple diagrams, diagrams in lists and block quotes, tilde fences, and non-Mermaid code render correctly. Incomplete or malformed diagrams display source and an error without affecting adjacent content.
3. Mixed responses preserve formatted tables and shell copy/paste buttons; ordinary code remains code. Markdown export and saved/reloaded conversation contain the original Mermaid source unchanged.
4. Diagrams render offline with network blocked. Attempts by diagram content to navigate, load remote assets or execute script do not succeed. No diagram source is sent to a remote service.
5. The image is readable in both IDE themes and at normal and HiDPI scaling. Resizing the chat does not distort or clip diagrams; large diagrams have a usable way to inspect them. Theme changes do not show a stale contrasting image.
6. Repeated display of the same diagram uses bounded caching. Clearing or closing a conversation during rendering does not leak browser instances, update a removed component, or freeze the interface. A long conversation remains responsive.
7. Where JCEF is unsupported, initialization fails, or a renderer times out, the source remains readable and retry/fallback behavior works.
8. Automated tests cover AST recognition, nesting, source preservation, cache keys and bounds, error/timeout handling, cancellation and source escaping. Integration tests exercise actual Mermaid rendering, PNG decoding, dimensions and mixed chat behavior. Manually verify visual quality, resizing, scrolling, theme changes, offline operation and resource cleanup in IntelliJ.

## Delivery

Package Mermaid and all required local assets with the plugin, document the bundled version and licenses, build the plugin, run focused automated tests and perform an IDE smoke test before installing. Record any unsupported Mermaid features explicitly; do not claim all diagram types work without verification.
