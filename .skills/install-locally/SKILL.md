---
name: install-locally
description: Build this project and install its plugin into the local IntelliJ IDEA installation when the user asks to install or update it locally.
---

Build the plugin from the current project using `./gradlew.bat buildPlugin` on Windows. Stop and report any build failure. Find the resulting ZIP in `build/distributions` and use the newly built artifact, not an older one.

The intended IDE is IntelliJ IDEA under `C:\Program Files\JetBrains` (currently `IntelliJ IDEA 2026.2.3`); do not select PyCharm. If multiple IntelliJ IDEA installations exist, use the user's specified version or ask which one to use.

Install by replacing the JARs from the ZIP's `IntelliJ Platform Plugin Template/lib/` directory in `%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\IntelliJ Platform Plugin Template\lib`. For a different IntelliJ version, first identify its corresponding user plugin directory. Replace only the JARs belonging to this plugin; do not use the IDE's plugin installation interface. Verify that the installed JARs match the built archive.

If IntelliJ is running, report that a restart is needed to load the update; do not close or restart it without the user's approval. Report the build and installation results, and distinguish a pending restart from a completed activation.
