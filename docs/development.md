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
│       └── main/resources/hr/avrbanac/snmptool/core/mib/ietf/   # bundled MIBs + index.list
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
- Parser and resolver tests use small inline MIB snippets (Java text blocks); larger fixtures can go under
  `core/src/test/resources/`.
- Each fix driven by a real-world MIB adds a minimal regression snippet (see `ResolverTest`
  `acceptsCommonVendorMistakes`).
- `BundledMibsTest` requires all bundled MIBs to load with zero diagnostics.
- Repository tests use `@TempDir`; nothing in tests may touch the real `~/.snmp-tool`.
- UI logic that can be separated from JavaFX rendering (e.g. `FilteredTree`, `TextFormat`) has unit tests
  in `app`.
- SNMP tests (M5) run against an in-process SNMP4J agent; no external devices in automated tests.

## Manual checks

- To try the app without touching your own MIB store, start it with a different home directory through
  the distribution's start script (not via `JAVA_TOOL_OPTIONS`, which would also move Gradle's home):
  ```sh
  ./gradlew :app:installDist
  JAVA_OPTS=-Duser.home=/tmp/snmp-tool-home app/build/install/app/bin/app
  ```
- Large-set behaviour was measured with the LibreNMS MIB collection (`mibs/` of
  github.com/librenms/librenms, ~4,900 files); see Performance in `docs/architecture.md`.

## Commits and versioning

- Branch `main` is always buildable.
- Commit subjects use conventional prefixes: `feat:`, `fix:`, `docs:`, `build:`, `test:`, `refactor:`, `chore:`.
- During initial development the version stays `0.1.0`; `CHANGELOG.md` records what was built under
  `[0.1.0]`, with one section per milestone.
- After the initial development phase, releases are tagged `vMAJOR.MINOR.PATCH` and changes go under
  `[Unreleased]` until the next release.
