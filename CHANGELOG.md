# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

During the initial development phase the version stays at **0.1.0** and all work is recorded under it,
grouped by milestone (see [docs/milestones.md](docs/milestones.md)). Regular versioning starts after the
initial development phase.

## [0.1.0] - in development

Done: M1–M5 (project skeleton, MIB parser and resolver, MIB browser, MIB import, SNMP operations). In
progress: M6 (polish and packaging).

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

### M4 — MIB import and repository

- Local MIB store in `~/.snmp-tool/mibs/`: one `<MODULE>.mib` file per module (files with several modules
  are split) and `index.properties` recording where and when each module was imported. Writes are atomic.
- Import of MIB files (Ctrl+O) or whole folders, searched recursively. Before anything is written, an
  import dialog lists every module found with its status (new, newer revision, older revision, changed,
  already imported), revision dates, parse problems and source file, and whether it replaces a bundled
  module; skipped files (no module, unreadable, duplicates) are listed separately.
- Missing dependencies are listed live for the current selection; modules can still be imported without
  them.
- Imported modules can replace bundled ones; removing them restores the bundled version. Removal asks for
  confirmation and lists the modules that import from the removed one.
- *MIB Modules* window (File → MIB modules…): all modules with origin, revision, problem count and import
  details, imported ones first, with filter, import, remove and "show problems"; a *Problems* tab lists
  every diagnostic with file and line, filterable. The status bar links to it with the problem count.
- Loading, import and removal run in the background; the tree keeps its selection across reloads.
- Parser records module start/end offsets (used to split multi-module files).
- More leniency for vendor MIBs, each reported as a warning: SMI type names in the wrong case (`INTEGER32`,
  `GAUGE`), lower-case type references (`SEQUENCE OF fooEntry`), and misspelled module names in IMPORTS
  (`RFC1212`, `RFC-1213`, ...), which also no longer count as missing dependencies in the import dialog.
- Performance for large MIB sets, measured with the LibreNMS collection (4,723 modules, 893k OID nodes):
  - resolver: fallback lookup of names that were not imported uses a name index built once (a 4,700-module
    load took over 10 minutes before);
  - `Oid` caches its hash code; the OID tree and model no longer copy their large maps;
  - repository caches module headers per stored file (size and modification time); listing modules dropped
    from ~1.8 s to ~0.2 s;
  - `MibSearch` is a per-model index built in the background with the model; name search dropped from up to
    380 ms to 2–16 ms, description search to ~11 ms;
  - result: reload ~5 s instead of ~11 s for the whole collection.
- Documentation brought in line with the implementation: README features and status, architecture
  (packages, data flow, threading, performance), MIB repository design, parser leniency rules, development
  guide (testing, manual checks) and CLAUDE.md rules (test isolation from `~/.snmp-tool`, large sets).
- Repository tests covering new, identical, newer, older and changed imports, bundled overrides, removal,
  missing dependencies, multi-module files, folder scans and problem reporting.

### M5 — SNMP operations

- SNMP client in `core.snmp` on SNMP4J 3.13 (kept internal): `SnmpSession` with get, getNext, walk (GETBULK,
  streamed in batches, cancellable, single-GET fallback for scalar instances) and set, over v2c and v3.
- SNMPv3 USM with all security levels; authentication MD5, SHA-1, SHA-224/256/384/512; privacy DES, 3DES,
  AES-128/192/256; context name and engine ID. Each session has its own USM; nothing is shared globally.
- Clear errors: timeouts with hints on what to check, error-status with the failing OID, v3 reports
  (unknown user, wrong digest, decryption error, ...), unknown hosts and invalid settings.
- MIB-aware results: instance names with decoded table indexes (integer, IpAddress, fixed-size,
  length-prefixed and IMPLIED strings, OIDs, AUGMENTS rows), enumeration labels, BITS names, RFC 2579
  DISPLAY-HINT formatting, TimeTicks durations, UNITS and symbolic OIDs, plus the raw value.
- Set input parsing and validation against the MIB type: enumeration labels or numbers, ranges, sizes, BITS,
  text or hex octets, symbolic OIDs, IP addresses.
- Operations panel: device chooser with new/edit/remove, OID field filled from the tree selection (scalars
  get `.0`), Get / Get Next (steps on) / Walk / Set… / Stop, status with timing, results table (name,
  value, type, OID, raw) with "Show in tree", "Use as OID", "Set…" and copy actions.
- Device dialog with v2c and v3 settings, masked secrets with a show toggle, live validation and a
  connection test; set dialog prefilled with the current value, type chooser, enumeration list, hex
  toggle, live validation and a read-only warning. Device profiles are kept in memory for now.
- Fixed: the details pane could keep showing the previous definition's fields (cached scroll viewport).
- In-process SNMP test agent and tests for sessions (v2c and all v3 levels), display hints, result
  formatting and set input parsing.

### M6 — Polish and packaging

- Device profiles are saved in `~/.snmp-tool/settings.json` (core: `SettingsStore`, `Settings`,
  `SavedDevice`) and restored at startup with the last selected device; changes are saved immediately. The
  file is written atomically with owner-only permissions.
- Per-device "Remember communities and passphrases" option: without it, secrets are not written to disk and
  are asked for the first time the device is used after each start (secrets dialog).
- Lenient settings loading: invalid values fall back to defaults, broken entries are skipped and reported at
  startup, and a file that is not valid JSON is moved to `settings.json.broken` instead of being overwritten.
- Removing a device asks for confirmation.
- Recent devices: the device chooser lists devices most recently used first; using or creating a device
  moves it to the top.
- The main window reopens with its last size, position and maximized state (a position no longer on any
  screen is ignored), divider positions and results column widths, and the last selected MIB tree node is
  selected again once the MIBs have loaded.
- Small built-in JSON reader/writer, so no JSON library is needed (ADR-007).
- Tests for the JSON reader/writer and the settings store (round trip, secrets handling, permissions,
  malformed files, invalid values, window layout and selection).
