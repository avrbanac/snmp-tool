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

### M2 — SMI parser and resolver

- In-house MIB parser for SMIv1 and SMIv2: lexer, hand-written recursive-descent parser and AST covering
  IMPORTS, MODULE-IDENTITY, OBJECT-IDENTITY, OBJECT-TYPE, NOTIFICATION-TYPE, TRAP-TYPE, TEXTUAL-CONVENTION,
  groups, compliance and capability statements, and type assignments (SEQUENCE, CHOICE, enumerations,
  BITS, size and range constraints).
- Lenient parsing: problems become diagnostics with file, line and column; broken definitions are skipped
  and parsing continues with the next one. Common vendor mistakes (missing `;` after IMPORTS, keyword case,
  missing commas, missing END, unimported names) are accepted with a warning.
- Resolver: imports across modules, object identifiers (forward and cross-module references, cycle
  detection), types through textual conventions down to SMI base types, effective constraints, enumerations
  and display hints. OBJECT-TYPEs are classified as table, row, column or scalar; SMIv1 traps are placed
  at `enterprise.0.n`.
- Resolved, immutable model (`MibModel`, `MibModule`, `MibNode`, `MibType`) and a merged `OidTree` with
  lookup by OID, name, `MODULE::name` and longest prefix (for instance OIDs). SMIv2 definitions take
  precedence over SMIv1 ones at the same OID.
- 61 bundled IETF and IANA MIB modules (SMI, SNMPv2-MIB, IF-MIB, IP-MIB, HOST-RESOURCES-MIB, SNMP framework
  and security MIBs, DISMAN, RMON, ...), all loading without diagnostics.
- Tests for the lexer, parser, error recovery, resolver and the bundled MIBs.

### M3 — MIB browser

- Bundled MIBs load in the background at startup; the status bar shows modules, nodes and problems.
- MIB tree built lazily as nodes are expanded, opening at `mib-2`. Each node shows a kind icon (scalar,
  table, row, column, notification, group, ...), its name and arc number; deprecated and obsolete
  definitions are dimmed or struck through.
- Search (Ctrl+F) by name, `MODULE::name` or numeric OID, optionally also in descriptions; an instance OID
  such as `ifDescr.3` finds `ifDescr`. The tree is filtered to matches and their ancestors, matches in
  bold:
  - a match with no deeper matches is collapsed and can be expanded into its full subtree;
  - a match or ancestor on the path to deeper matches shows only that path; "Show all children" in the
    context menu adds the hidden siblings;
  - "Show in full tree" (double-click, Enter or context menu) clears the search and selects the node in
    the full tree. Esc clears the search and keeps the selection.
- Details pane: OID, symbolic path, module, kind, status, syntax with type chain, display hint,
  enumerations/bits, size and range, access, units, index, augments, columns, default value, group
  members, reference and source location; module identities also show last update, organization,
  contact, revisions and imports. Descriptions are shown without the source indentation.
- When several modules define the same OID (e.g. `RFC1213-MIB` and `IF-MIB`), a chooser switches between
  the definitions.
- Copy name, `MODULE::name` or OID from the tree context menu, the details pane or with Ctrl+C.
- Core: `MibSearch` (name, `MODULE::name`, OID and description search), `OidTree.stream()` and
  `Node.symbolicPath()`.
- Tests for search, the filtered tree and description/date formatting.
