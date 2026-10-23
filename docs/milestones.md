# Milestones

Each milestone ends with a working, committed state. Scope listed here is the contract for the milestone;
anything else goes into a later milestone or the backlog.

## M1 — Project skeleton ✅

- Gradle multi-project build (Kotlin DSL) with wrapper: `core` and `app` subprojects.
- Java 25 toolchain, JavaFX 25 via the OpenJFX Gradle plugin, JUnit 6.
- `app` opens an empty main window with the final layout placeholders:
  tree (left), details (right), operations/results (bottom).
- `.gitignore`, `.editorconfig`.
- `./gradlew build` and `./gradlew :app:run` work.

**Done when:** a clean checkout builds, tests run (even if trivial), and the window opens.

## M2 — SMI parser and resolver ✅

- Lexer: identifiers, numbers, hex/binary strings, quoted strings (multi-line), `--` comments, symbols.
- Parser (hand-written recursive descent) producing an AST per module:
  `IMPORTS`, `MODULE-IDENTITY`, `OBJECT IDENTIFIER` assignments, `OBJECT-TYPE`, `OBJECT-IDENTITY`,
  `TEXTUAL-CONVENTION`, `NOTIFICATION-TYPE`, `TRAP-TYPE`, `OBJECT-GROUP`, `NOTIFICATION-GROUP`,
  `MODULE-COMPLIANCE`, `AGENT-CAPABILITIES`, type assignments including `SEQUENCE`, `CHOICE`, constraints.
- Lenient error recovery with diagnostics (severity, file, line, column, message).
- Resolver: imports across modules, OID resolution (including forward and cross-module references),
  type resolution through textual conventions.
- Built-in definitions for the SMI macros and base types.
- Bundled IETF base MIBs as resources; all of them parse and resolve without errors.

**Done when:** the bundled IETF MIBs load into a resolved model with full numeric OIDs, covered by tests.

## M3 — MIB browser ✅

- Merged OID tree in a JavaFX `TreeView`, lazily populated.
- Node icons/styling by kind (object, table, row, column, notification, group, ...).
- Search/filter by name, `MODULE::name`, numeric (or instance) OID, or description text. Matches stay
  expandable; "Show all children" and "Show in full tree" give the surrounding context on request.
- Details pane: name, module, numeric and symbolic OID, kind, syntax (with resolved base type and
  constraints), access, status, units, index/augments, enumerations, DEFVAL, description, reference.
- Copy OID / name to clipboard.

**Done when:** the bundled MIBs can be browsed and every node shows its full definition.

## M4 — MIB import and repository ✅

- Local repository directory (default `~/.snmp-tool/mibs`), one file per module, with an index of import
  origins.
- Import single files or folders (recursively); detect module name(s) in each file; split files with
  several modules.
- Import review: new / newer / older / changed / already imported, bundled overrides, skipped files.
- Dependencies from `IMPORTS`; missing modules reported by name in the import dialog and as diagnostics.
- Revision comparison (`LAST-UPDATED` / `REVISION`) on re-import; replacing a module reloads the model.
- Remove a module (with warning about dependants; bundled version restored if it was overridden).
- *MIB Modules* window with a *Problems* tab listing parser/resolver diagnostics, filterable per module.
- Works with large sets (LibreNMS collection, 4,700+ modules: reload ~5 s, search in milliseconds).

**Done when:** vendor MIBs can be imported, updated and removed, and the tree reflects the change.

## M5 — SNMP operations ✅

- Device profiles: host, port, timeout, retries, max-repetitions, version (kept in memory; saved in M6).
- v2c: community strings (read and write).
- v3: USM user, security level, auth protocol and key, privacy protocol and key, context.
- Operations from the selected node: get, getNext, walk (GETBULK for v2c/v3), set.
- Results table: OID, name (resolved via the MIB model), type, raw value, formatted value
  (enumeration labels, display hints).
- Set dialog with type-aware input validation.
- Cancel for running walks.

**Done when:** all four operations work against a real or simulated agent for v2c and v3.
(Verified against the in-process test agent, including every v3 security level.)

## M6 — Polish and packaging

- ✅ Persist device profiles in `~/.snmp-tool/settings.json`; secrets saved only when "remember" is on,
  otherwise asked for once per run (see `docs/snmp-operations.md`).
- ✅ Recent devices (chooser ordered most recently used first), window layout (size, position, maximized,
  dividers, column widths) and last tree selection, restored at startup.
- ✅ Export walk results (CSV, text), all rows or the selection.
- ✅ jpackage packaging: `./gradlew :app:jpackage` (app image by default; `-PpackageType=deb|rpm|msi|dmg`
  on the matching platform), with the application icon.
- First tagged release `v1.0.0`.

## Backlog (unscheduled)

- Trap/inform receiver.
- Table view for conceptual tables (rows × columns).
- MIB source viewer with jump-to-definition.
- Compare two revisions of a module.
