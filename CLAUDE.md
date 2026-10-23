# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

A JavaFX desktop **MIB browser** with basic SNMP operations (get, getNext, walk, set over v2c and v3).
Internal-use tool. The MIB parser is written in-house; do not introduce Mibble, SNMP4J-SMI, libsmi or
other third-party MIB parsers (see `docs/decisions.md`, ADR-003).

## Read before working

Read the document relevant to the task before changing code:

- `docs/milestones.md` — roadmap; check which milestone is current and stay within its scope.
- `docs/architecture.md` — module layout, package structure, dependency rules.
- `docs/mib-parser.md` — lexer, parser, resolver and model design.
- `docs/mib-repository.md` — MIB store, import planning, update and removal flow.
- `docs/snmp-operations.md` — SNMP client design, v2c/v3 profiles, value decoding.
- `docs/development.md` — build commands, coding conventions, testing, commit rules.
- `docs/decisions.md` — architecture decision records; add a new ADR for any significant decision.

## Stack

- Java 25, JavaFX 25, Gradle (Kotlin DSL) via `./gradlew`, SNMP4J, JUnit 6.
- Gradle subprojects: `core` (no JavaFX dependency) and `app` (JavaFX UI); both are JPMS modules.
- Versions in `gradle/libs.versions.toml`; shared build logic in `buildSrc`.
- Compiler runs with `-Xlint:all -Werror`: fix warnings, don't suppress them without reason.
- Base package: `hr.avrbanac.snmptool`.

## Commands

```sh
./gradlew build          # compile + test everything
./gradlew :core:test     # core tests only
./gradlew :app:run       # run the application
```

## Rules

- `core` must never depend on JavaFX. The UI talks to `core` only through its public API.
- The parser must be **lenient**: report problems as diagnostics with file/line/column and keep going.
  Never throw on malformed vendor MIBs unless the file is unreadable.
- Every parser or resolver fix that comes from a real MIB gets a regression test with a minimal MIB
  snippet (inline in `ParserTest` / `ResolverTest`, or a file under `core/src/test/resources/` if large).
- All bundled MIBs must keep loading with zero diagnostics (`BundledMibsTest`).
- Tests and manual runs must never touch the user's real `~/.snmp-tool`: use `@TempDir` /
  `new MibRepository(dir)` / `new SettingsStore(file)` in tests, and `-Duser.home=<temp dir>` when launching
  the app for checks (see Manual checks in `docs/development.md`).
- Keep large MIB sets in mind (see Performance in `docs/architecture.md`): no per-node or per-module work
  that scans all modules, and nothing proportional to the model size on the JavaFX thread.
- Long-running work (MIB loading, SNMP requests) never runs on the JavaFX application thread.
- SNMP4J stays inside `core.snmp`; the public API uses `SnmpValue`, `VarBindResult` and `SnmpException`.
  Secrets (communities, passphrases) are never logged or included in `toString()`.
- Version stays `0.1.0` during initial development. Record changes in `CHANGELOG.md` under `[0.1.0]`,
  in the section of the current milestone (`### M<n> — <title>`), as a general summary of what was built.
- Keep docs in `docs/` in sync when a design changes.
- Commit only when asked. Conventional-style subjects (`feat:`, `fix:`, `docs:`, `build:`, `test:`, `refactor:`).
