---
name: ci-checks
description: Run and interpret this plugin project's Gradle CI validation checks, including formatting, duplicate-code detection, static analysis, tests, and plugin verification.
---

Use this skill when the user asks to run, diagnose, or choose this repository's CI checks.

Run the narrowest check that validates the requested change. Use `./gradlew check verifyPlugin` for the same validation performed before a release.

| Command | Coverage |
| --- | --- |
| `./gradlew compileKotlin` | Compiles production Kotlin; use as the fastest build check. |
| `./gradlew ktlintCheck` | Checks Kotlin formatting. |
| `./gradlew cpdCheck` | Detects duplicated Kotlin code with PMD CPD. |
| `./gradlew detekt` | Runs aggregate static analysis. |
| `./gradlew test` | Runs the test suite. |
| `./gradlew check` | Runs CPD, type-resolved Detekt checks, ktlint, and tests. |
| `./gradlew verifyPlugin` | Checks IntelliJ Platform compatibility. |

The pull-request workflow runs `ktlintCheck`, `check`, and `verifyPlugin` in separate jobs. The release workflow runs `check verifyPlugin` before building the distribution.

## Duplicate-code detection

`cpdCheck` scans production Kotlin in the plugin and custom Detekt module. Its defaults live in `gradle.properties` and may be overridden for one run:

```bash
./gradlew cpdCheck -PcpdMinimumTokens=80 -PcpdIncludeTests=true
```

- `cpdMinimumTokens` controls the smallest reported clone.
- `cpdIncludeTests` adds test sources.
- `cpdIgnoreIdentifiers` detects copies with renamed variables.
- `cpdIgnoreLiterals` detects copies with changed literal values.
- `cpdFailOnViolation` controls whether duplicates fail the task.

For an intentional exceptional block, exclude only that block by surrounding it with `// CPD-OFF` and `// CPD-ON` comments. Do not use this to silence an ordinary duplicate; refactor it instead.
