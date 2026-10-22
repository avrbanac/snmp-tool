# Development guide

## Prerequisites

- JDK 25 on `PATH` (or discoverable by Gradle toolchains).
- No local Gradle needed; use the wrapper `./gradlew`.

## Common commands

```sh
./gradlew build              # compile and test all subprojects
./gradlew :core:test         # core tests only
./gradlew :app:run           # start the application
./gradlew :app:jpackage      # build a native installer (from M6)
```

## Repository layout

```
snmp-tool/
├── core/                    # MIB parser, model, repository, SNMP client (no JavaFX)
│   └── src/{main,test}/{java,resources}
├── app/                     # JavaFX UI
│   └── src/{main,test}/{java,resources}
├── docs/                    # design documents
├── buildSrc/                # shared build logic (snmptool.java-conventions plugin)
├── gradle/                  # wrapper and version catalog (libs.versions.toml)
├── CHANGELOG.md
├── CLAUDE.md
└── README.md
```

Dependency versions live in `gradle/libs.versions.toml`. Settings shared by all subprojects
(toolchain, compiler flags, JUnit) live in `buildSrc/src/main/kotlin/snmptool.java-conventions.gradle.kts`.

Both subprojects are JPMS modules (`hr.avrbanac.snmptool.core`, `hr.avrbanac.snmptool.app`).
Compilation uses `-Xlint:all -Werror`, so warnings fail the build.

## Coding conventions

- Java 25 language features are welcome: records, sealed interfaces, pattern matching, `switch` expressions.
- Model classes are immutable (records where practical).
- No `null` in public APIs of `core`; use `Optional` for absent values and empty collections for none.
- Packages follow `docs/architecture.md`. `core` never imports `javafx.*`.
- 4-space indentation, UTF-8, LF line endings (enforced via `.editorconfig`).
- Logging through `System.Logger`; no logging framework dependency unless needed.

## Testing

- JUnit 6 for all tests.
- Parser tests use small inline MIB snippets or files under `core/src/test/resources/`.
- Each fix driven by a real-world MIB adds a minimal regression snippet under
  `core/src/test/resources/quirks/`.
- SNMP tests run against an in-process SNMP4J agent; no external devices in automated tests.

## Commits and versioning

- Branch `main` is always buildable.
- Commit subjects use conventional prefixes: `feat:`, `fix:`, `docs:`, `build:`, `test:`, `refactor:`, `chore:`.
- During initial development the version stays `0.1.0`; `CHANGELOG.md` records what was built under
  `[0.1.0]`, with one section per milestone.
- After the initial development phase, releases are tagged `vMAJOR.MINOR.PATCH` and changes go under
  `[Unreleased]` until the next release.
