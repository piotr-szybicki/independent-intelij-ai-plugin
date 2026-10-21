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
