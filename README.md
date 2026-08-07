# independent-intelij-ai-plugin

![Build](https://github.com/piotr-szybicki/independent-intelij-ai-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [group](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml), [name](./src/main/resources/META-INF/plugin.xml), and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin [description](./src/main/resources/META-INF/plugin.xml) (see [Tips][docs:plugin-description]) and this README to describe what your plugin does.
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

This Fancy IntelliJ Platform Plugin is going to be your implementation of the brilliant ideas that you have.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "independent-intelij-ai-plugin"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/piotr-szybicki/independent-intelij-ai-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


## Development

### Prerequisites

- **JDK 21** — the 2025.2 platform targets it, and Gradle builds with whatever JVM it runs on.
- **IntelliJ IDEA 2025.2.x** to install into. The plugin is built against `2025.2.6.2`; it also
  depends on the bundled Terminal plugin and the VCS platform module, both of which ship with IDEA.
- An Anthropic API key in the **`AI_API_KEY`** environment variable. The plugin reads it from the
  environment at runtime — there is no field to paste it into.

### Running it

`runIde` starts a separate sandbox IDE with the plugin installed. It does not touch your day-to-day
IDE or its settings.

```bash
./gradlew runIde
```

On Windows, use `gradlew.bat runIde`.

The sandbox inherits Gradle's environment, so set the key in the **same shell** before launching, or
the chat will start with no credentials:

```bash
$env:AI_API_KEY = "sk-ant-..."; .\gradlew.bat runIde
```

The bash equivalent is `AI_API_KEY=sk-ant-... ./gradlew runIde`.

First run downloads the target IDE (~1 GB) and takes a while; later runs reuse it.

### Packaging it

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
<kbd>Install Plugin from Disk…</kbd>, then restart. Remember the IDE only picks up `AI_API_KEY` at
startup, so set it as a user environment variable rather than in a shell — an IDE launched from a
desktop shortcut or Toolbox will not see a variable exported in your shell profile.

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
