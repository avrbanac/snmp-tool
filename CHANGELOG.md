# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

During the initial development phase the version stays at **0.1.0** and all work is recorded under it,
grouped by milestone (see [docs/milestones.md](docs/milestones.md)). Regular versioning starts after the
initial development phase.

## [0.1.0] - in development

### M1 — Project skeleton

- Project documentation: README, changelog, CLAUDE.md and the `docs/` folder
  (architecture, milestones, MIB parser design, MIB repository design, SNMP operations,
  development guide and decision log).
- Gradle 9.8.2 multi-project build with wrapper: `core` (MIB and SNMP logic, no JavaFX) and
  `app` (JavaFX UI), both as JPMS modules.
- Java 25 toolchain, version catalog (`gradle/libs.versions.toml`) and a shared convention plugin
  in `buildSrc`; compiler warnings fail the build.
- JavaFX 25 main window with the planned layout as placeholders: MIB tree with search box (left),
  details pane (right), operations pane (bottom), plus menu bar (File, Help → About) and status bar.
- `Oid` value type for numeric object identifiers (parsing, parent/child navigation, prefix checks,
  walk ordering), with unit tests.
